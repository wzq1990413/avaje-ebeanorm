package com.avaje.ebeaninternal.util;

import java.sql.SQLException;
import java.util.ArrayList;

import com.avaje.ebeaninternal.api.SpiExpressionList;
import com.avaje.ebeaninternal.api.SpiExpressionRequest;
import com.avaje.ebeaninternal.server.core.JsonExpressionHandler;
import com.avaje.ebeaninternal.server.core.SpiOrmQueryRequest;
import com.avaje.ebeaninternal.server.deploy.BeanDescriptor;
import com.avaje.ebeaninternal.server.deploy.DeployParser;
import com.avaje.ebeaninternal.server.persist.Binder;
import com.avaje.ebeaninternal.server.type.DataBind;

public class DefaultExpressionRequest implements SpiExpressionRequest {

  private final SpiOrmQueryRequest<?> queryRequest;

  private final BeanDescriptor<?> beanDescriptor;

  private final StringBuilder sql = new StringBuilder();

  private final ArrayList<Object> bindValues = new ArrayList<Object>();

  private final DeployParser deployParser;

  private final Binder binder;

  private final SpiExpressionList<?> expressionList;

  private int paramIndex;

  private StringBuilder bindLog;

  public DefaultExpressionRequest(SpiOrmQueryRequest<?> queryRequest, DeployParser deployParser, Binder binder, SpiExpressionList<?> expressionList) {
    this.queryRequest = queryRequest;
    this.beanDescriptor = queryRequest.getBeanDescriptor();
    this.deployParser = deployParser;
    this.binder = binder;
    this.expressionList = expressionList;
    // immediately build the list of bind values (callback style)
    expressionList.buildBindValues(this);
  }

  public DefaultExpressionRequest(BeanDescriptor<?> beanDescriptor) {
    this.beanDescriptor = beanDescriptor;
    this.queryRequest = null;
    this.deployParser = null;
    this.binder = null;
    this.expressionList = null;
  }

  /**
   * Build sql for the underlying expression list.
   */
  public String buildSql() {
    return expressionList.buildSql(this);
  }

  /**
   * Bind the values from the underlying expression list.
   */
  public void bind(DataBind dataBind) throws SQLException {
    for (int i = 0; i < bindValues.size(); i++) {
      Object bindValue = bindValues.get(i);
      binder.bindObject(dataBind, bindValue);
    }
    if (bindLog != null) {
      dataBind.append(bindLog.toString());
    }
  }

  public JsonExpressionHandler getJsonHandler() {
    return binder.getJsonExpressionHandler();
  }

  public String parseDeploy(String logicalProp) {

    String s = deployParser.getDeployWord(logicalProp);
    return s == null ? logicalProp : s;
  }

  /**
   * Append the database platform like clause.
   */
  @Override
  public void appendLike() {
    sql.append(" ");
    sql.append(queryRequest.getDBLikeClause());
    sql.append(" ");
  }

  /**
   * Increments the parameter index and returns that value.
   */
  public int nextParameter() {
    return ++paramIndex;
  }

  public BeanDescriptor<?> getBeanDescriptor() {
    return beanDescriptor;
  }

  public SpiOrmQueryRequest<?> getQueryRequest() {
    return queryRequest;
  }

  /**
   * Append text the underlying sql expression.
   */
  public SpiExpressionRequest append(String sqlExpression) {
    sql.append(sqlExpression);
    return this;
  }

  public void addBindEncryptKey(Object bindValue) {
    bindValues.add(bindValue);
    bindLog("****");
  }

  public void addBindValue(Object bindValue) {
    bindValues.add(bindValue);
    bindLog(bindValue);
  }

  private void bindLog(Object val) {
    if (bindLog == null) {
      bindLog = new StringBuilder();
    } else {
      bindLog.append(",");
    }
    bindLog.append(val);
  }

  public String getBindLog() {
    return bindLog == null ? "" : bindLog.toString();
  }

  public String getSql() {
    return sql.toString();
  }

  public ArrayList<Object> getBindValues() {
    return bindValues;
  }

}
