package com.avaje.ebeaninternal.server.loadcontext;

import com.avaje.ebean.bean.BeanCollection;
import com.avaje.ebean.bean.EntityBeanIntercept;
import com.avaje.ebean.bean.ObjectGraphNode;
import com.avaje.ebean.bean.ObjectGraphOrigin;
import com.avaje.ebean.bean.PersistenceContext;
import com.avaje.ebeaninternal.api.LoadContext;
import com.avaje.ebeaninternal.api.LoadSecondaryQuery;
import com.avaje.ebeaninternal.api.SpiEbeanServer;
import com.avaje.ebeaninternal.api.SpiQuery;
import com.avaje.ebeaninternal.server.core.OrmQueryRequest;
import com.avaje.ebeaninternal.server.deploy.BeanDescriptor;
import com.avaje.ebeaninternal.server.deploy.BeanProperty;
import com.avaje.ebeaninternal.server.deploy.BeanPropertyAssoc;
import com.avaje.ebeaninternal.server.deploy.BeanPropertyAssocMany;
import com.avaje.ebeaninternal.server.el.ElPropertyValue;
import com.avaje.ebeaninternal.server.querydefn.OrmQueryProperties;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of LoadContext.
 */
public class DLoadContext implements LoadContext {

  private final SpiEbeanServer ebeanServer;

  private final BeanDescriptor<?> rootDescriptor;

  private final Map<String, DLoadBeanContext> beanMap = new HashMap<String, DLoadBeanContext>();
  private final Map<String, DLoadManyContext> manyMap = new HashMap<String, DLoadManyContext>();

  private final DLoadBeanContext rootBeanContext;

  private final boolean asDraft;
  private final Timestamp asOf;
  private final Boolean readOnly;
  private final boolean excludeBeanCache;
  private final int defaultBatchSize;
  private final boolean disableLazyLoading;
  private final boolean disableReadAudit;
  private final boolean includeSoftDeletes;

  /**
   * The path relative to the root of the object graph.
   */
  private final String relativePath;
  private final ObjectGraphOrigin origin;
  private final boolean useProfiling;

  private final Map<String, ObjectGraphNode> nodePathMap = new HashMap<String, ObjectGraphNode>();

  private PersistenceContext persistenceContext;

  private List<OrmQueryProperties> secQuery;

  public DLoadContext(OrmQueryRequest<?> request) {

    this.persistenceContext = request.getPersistenceContext();
    this.ebeanServer = request.getServer();
    this.defaultBatchSize = request.getLazyLoadBatchSize();
    this.rootDescriptor = request.getBeanDescriptor();

    SpiQuery<?> query = request.getQuery();
    this.asOf = query.getAsOf();
    this.asDraft = query.isAsDraft();
    this.includeSoftDeletes = query.isIncludeSoftDeletes();
    this.readOnly = query.isReadOnly();
    this.disableReadAudit = query.isDisableReadAudit();
    this.disableLazyLoading = query.isDisableLazyLoading();
    this.excludeBeanCache = Boolean.FALSE.equals(query.isUseBeanCache());
    this.useProfiling = query.getProfilingListener() != null;

    ObjectGraphNode parentNode = query.getParentNode();
    if (parentNode != null) {
      this.origin = parentNode.getOriginQueryPoint();
      this.relativePath = parentNode.getPath();
    } else {
      this.origin = null;
      this.relativePath = null;
    }

    // initialise rootBeanContext after origin and relativePath have been set
    this.rootBeanContext = new DLoadBeanContext(this, rootDescriptor, null, defaultBatchSize, null);
  }

  protected boolean isExcludeBeanCache() {
    return excludeBeanCache;
  }

  /**
   * Return the minimum batch size when using QueryIterator with query joins.
   */
  public int getSecondaryQueriesMinBatchSize(int defaultQueryBatch) {

    if (secQuery == null) {
      return -1;
    }

    int maxBatch = 0;
    for (int i = 0; i < secQuery.size(); i++) {
      int batchSize = secQuery.get(i).getQueryFetchBatch();
      if (batchSize == 0) {
        batchSize = defaultQueryBatch;
      }
      maxBatch = Math.max(maxBatch, batchSize);
    }
    return maxBatch;
  }

  /**
   * Execute all the secondary queries.
   */
  public void executeSecondaryQueries(OrmQueryRequest<?> parentRequest) {

    if (secQuery != null) {
      for (int i = 0; i < secQuery.size(); i++) {
        OrmQueryProperties properties = secQuery.get(i);
        LoadSecondaryQuery load = getLoadSecondaryQuery(properties.getPath());
        load.loadSecondaryQuery(parentRequest);
      }
    }
  }

  /**
   * Return the LoadBeanContext or LoadManyContext for the given path.
   */
  private LoadSecondaryQuery getLoadSecondaryQuery(String path) {
    LoadSecondaryQuery beanLoad = beanMap.get(path);
    if (beanLoad == null) {
      beanLoad = manyMap.get(path);
    }
    return beanLoad;
  }

  /**
   * Remove the +query and +lazy secondary queries and
   * register them with their appropriate LoadBeanContext
   * or LoadManyContext.
   * <p>
   * The parts of the secondary queries are removed and used
   * by LoadBeanContext/LoadManyContext to build the appropriate
   * queries.
   * </p>
   */
  public void registerSecondaryQueries(SpiQuery<?> query) {

    secQuery = query.removeQueryJoins();
    if (secQuery != null) {
      for (int i = 0; i < secQuery.size(); i++) {
        OrmQueryProperties props = secQuery.get(i);
        registerSecondaryQuery(props);
      }
    }

    List<OrmQueryProperties> lazyQueries = query.removeLazyJoins();
    if (lazyQueries != null) {
      for (int i = 0; i < lazyQueries.size(); i++) {
        OrmQueryProperties lazyProps = lazyQueries.get(i);
        registerSecondaryQuery(lazyProps);
      }
    }
  }

  /**
   * Setup the load context at this path with OrmQueryProperties which is
   * used to build the appropriate query for +query or +lazy loading.
   */
  private void registerSecondaryQuery(OrmQueryProperties props) {

    String propName = props.getPath();
    ElPropertyValue elGetValue = rootDescriptor.getElGetValue(propName);

    boolean many = elGetValue.getBeanProperty().containsMany();
    registerSecondaryNode(many, props);
  }


  public ObjectGraphNode getObjectGraphNode(String path) {

    ObjectGraphNode node = nodePathMap.get(path);
    if (node == null) {
      node = createObjectGraphNode(path);
      nodePathMap.put(path, node);
    }

    return node;
  }

  private ObjectGraphNode createObjectGraphNode(String path) {

    if (relativePath != null) {
      if (path == null) {
        path = relativePath;
      } else {
        path = relativePath + "." + path;
      }
    }
    return new ObjectGraphNode(origin, path);
  }

  protected String getFullPath(String path) {
    if (relativePath == null) {
      return path;
    } else {
      return relativePath + "." + path;
    }
  }

  protected SpiEbeanServer getEbeanServer() {
    return ebeanServer;
  }

  /**
   * Return the parent state which defines the sharedInstance and readOnly status
   * which needs to be propagated to other beans and collections.
   */
  protected Boolean isReadOnly() {
    return readOnly;
  }

  public PersistenceContext getPersistenceContext() {
    return persistenceContext;
  }

  public void resetPersistenceContext(PersistenceContext persistenceContext) {
    this.persistenceContext = persistenceContext;
    // clear the load contexts for beans and beanCollections
    for (DLoadBeanContext beanContext : beanMap.values()) {
      beanContext.clear();
    }
    for (DLoadManyContext manyContext : manyMap.values()) {
      manyContext.clear();
    }
    this.rootBeanContext.clear();
  }

  public void register(String path, EntityBeanIntercept ebi) {
    getBeanContext(path).register(ebi);
  }

  public void register(String path, BeanCollection<?> bc) {
    getManyContext(path).register(bc);
  }

  private DLoadBeanContext getBeanContext(String path) {
    if (path == null) {
      return rootBeanContext;
    }
    DLoadBeanContext beanContext = beanMap.get(path);
    if (beanContext == null) {
      beanContext = createBeanContext(path, defaultBatchSize, null);
      beanMap.put(path, beanContext);
    }
    return beanContext;
  }

  private void registerSecondaryNode(boolean many, OrmQueryProperties props) {

    String path = props.getPath();
    int lazyJoinBatch = props.getLazyFetchBatch();
    int batchSize = lazyJoinBatch > 0 ? lazyJoinBatch : defaultBatchSize;

    if (many) {
      DLoadManyContext manyContext = createManyContext(path, batchSize, props);
      manyMap.put(path, manyContext);
    } else {
      DLoadBeanContext beanContext = createBeanContext(path, batchSize, props);
      beanMap.put(path, beanContext);
    }
  }

  private DLoadManyContext getManyContext(String path) {
    if (path == null) {
      throw new RuntimeException("path is null?");
    }
    DLoadManyContext ctx = manyMap.get(path);
    if (ctx == null) {
      ctx = createManyContext(path, defaultBatchSize, null);
      manyMap.put(path, ctx);
    }
    return ctx;
  }

  private DLoadManyContext createManyContext(String path, int batchSize, OrmQueryProperties queryProps) {

    BeanPropertyAssocMany<?> p = (BeanPropertyAssocMany<?>) getBeanProperty(rootDescriptor, path);

    return new DLoadManyContext(this, p, path, batchSize, queryProps);
  }

  private DLoadBeanContext createBeanContext(String path, int batchSize, OrmQueryProperties queryProps) {

    BeanPropertyAssoc<?> p = (BeanPropertyAssoc<?>) getBeanProperty(rootDescriptor, path);
    BeanDescriptor<?> targetDescriptor = p.getTargetDescriptor();

    return new DLoadBeanContext(this, targetDescriptor, path, batchSize, queryProps);
  }

  private BeanProperty getBeanProperty(BeanDescriptor<?> desc, String path) {
    return desc.getBeanPropertyFromPath(path);
  }

  /**
   * Propagate the original query settings (draft, asOf etc) to the secondary queries.
   */
  public void propagateQueryState(SpiQuery<?> query) {
    if (readOnly != null) {
      query.setReadOnly(readOnly);
    }
    query.setDisableLazyLoading(disableLazyLoading);
    query.asOf(asOf);
    if (asDraft) {
      query.asDraft();
    }
    if (includeSoftDeletes) {
      query.includeSoftDeletes();
    }
    if (disableReadAudit) {
      query.setDisableReadAuditing();
    }
    if (useProfiling) {
      query.setAutoTune(true);
    }
  }
}
