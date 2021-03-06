create table persons (
  id                            bigint not null,
  surname                       varchar(64) not null,
  name                          varchar(64) not null,
  constraint pk_persons primary key (id)
);
create sequence PERSONS_seq start with 1000 increment by 40;

create table phones (
  id                            bigint not null,
  phone_number                  varchar(7) not null,
  person_id                     bigint not null,
  constraint uq_phones_phone_number unique (phone_number),
  constraint pk_phones primary key (id)
);
create sequence PHONES_seq;

alter table phones add constraint fk_phones_person_id foreign key (person_id) references persons (id) on delete restrict on update restrict;
create index ix_phones_person_id on phones (person_id);

