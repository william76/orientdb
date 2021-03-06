/*
 * Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.test.database.auto;

import com.orientechnologies.DatabaseAbstractTest;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.db.document.ODatabaseDocument;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.index.*;
import com.orientechnologies.orient.core.iterator.ORecordIteratorCluster;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OClass.INDEX_TYPE;
import com.orientechnologies.orient.core.metadata.schema.OProperty;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.OCommandSQL;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;
import com.orientechnologies.orient.core.storage.ORecordDuplicatedException;
import com.orientechnologies.orient.core.storage.OStorageProxy;
import com.orientechnologies.orient.core.storage.cache.OWriteCache;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.tx.OTransaction;
import com.orientechnologies.orient.test.database.base.OrientTest;
import com.orientechnologies.orient.test.domain.business.Account;
import com.orientechnologies.orient.test.domain.whiz.Profile;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.impls.orient.*;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.util.*;

import static com.orientechnologies.DatabaseAbstractTest.getEnvironment;

@Test(groups = { "index" })
public class IndexTest extends ObjectDBBaseTest {
  @Parameters(value = "url")
  public IndexTest(@Optional String url) {
    super(url);
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    database.getEntityManager().registerEntityClasses("com.orientechnologies.orient.test.domain.business");
    database.getEntityManager().registerEntityClasses("com.orientechnologies.orient.test.domain.whiz");
    database.getEntityManager().registerEntityClasses("com.orientechnologies.orient.test.domain.base");
  }

  public void testDuplicatedIndexOnUnique() {
    Profile jayMiner = new Profile("Jay", "Jay", "Miner", null);
    database.save(jayMiner);

    Profile jacobMiner = new Profile("Jay", "Jacob", "Miner", null);

    try {
      database.save(jacobMiner);

      // IT SHOULD GIVE ERROR ON DUPLICATED KEY
      Assert.assertTrue(false);

    } catch (ORecordDuplicatedException e) {
      Assert.assertTrue(true);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInUniqueIndex() {
    final OProperty nickProperty = database.getMetadata().getSchema().getClass("Profile").getProperty("nick");
    Assert.assertEquals(nickProperty.getIndexes().iterator().next().getType(), OClass.INDEX_TYPE.UNIQUE.toString());

    final boolean localStorage = !(database.getStorage() instanceof OStorageProxy);

    boolean oldRecording = true;
    long indexQueries = 0L;
    if (localStorage) {
      oldRecording = Orient.instance().getProfiler().isRecording();

      if (!oldRecording) {
        Orient.instance().getProfiler().startRecording();
      }

      indexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
      if (indexQueries < 0) {
        indexQueries = 0;
      }
    }

    final List<Profile> result = database.command(new OSQLSynchQuery<Profile>(
        "SELECT * FROM Profile WHERE nick in ['ZZZJayLongNickIndex0' ,'ZZZJayLongNickIndex1', 'ZZZJayLongNickIndex2']")).execute();

    final List<String> expectedSurnames = new ArrayList<String>(Arrays.asList("NolteIndex0", "NolteIndex1", "NolteIndex2"));

    if (localStorage && !oldRecording) {
      Orient.instance().getProfiler().stopRecording();
    }

    Assert.assertEquals(result.size(), 3);
    for (final Profile profile : result) {
      expectedSurnames.remove(profile.getSurname());
    }

    Assert.assertEquals(expectedSurnames.size(), 0);

    if (localStorage) {
      final long newIndexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
      Assert.assertEquals(newIndexQueries, indexQueries + 1);
    }
  }

  @Test(dependsOnMethods = "testDuplicatedIndexOnUnique")
  public void testUseOfIndex() {
    final List<Profile> result = database.command(new OSQLSynchQuery<Profile>("select * from Profile where nick = 'Jay'"))
        .execute();

    Assert.assertFalse(result.isEmpty());

    Profile record;
    for (int i = 0; i < result.size(); ++i) {
      record = result.get(i);

      OrientTest.printRecord(i, record);

      Assert.assertTrue(record.getName().toString().equalsIgnoreCase("Jay"));
    }
  }

  @Test(dependsOnMethods = "testDuplicatedIndexOnUnique")
  public void testIndexEntries() {
    List<Profile> result = database.command(new OSQLSynchQuery<Profile>("select * from Profile where nick is not null")).execute();

    OIndex<?> idx = database.getMetadata().getIndexManager().getIndex("Profile.nick");

    Assert.assertEquals(idx.getSize(), result.size());
  }

  @Test(dependsOnMethods = "testDuplicatedIndexOnUnique")
  public void testIndexSize() {
    List<Profile> result = database.command(new OSQLSynchQuery<Profile>("select * from Profile where nick is not null")).execute();

    int profileSize = result.size();

    database.getMetadata().getIndexManager().reload();
    Assert.assertEquals(database.getMetadata().getIndexManager().getIndex("Profile.nick").getSize(), profileSize);
    for (int i = 0; i < 10; i++) {
      Profile profile = new Profile("Yay-" + i, "Jay", "Miner", null);
      database.save(profile);
      profileSize++;
      Assert.assertNotNull(database.getMetadata().getIndexManager().getIndex("Profile.nick").get("Yay-" + i));
    }
  }

  @Test(dependsOnMethods = "testUseOfIndex")
  public void testChangeOfIndexToNotUnique() {
    database.getMetadata().getSchema().getClass("Profile").getProperty("nick").dropIndexes();
    database.getMetadata().getSchema().getClass("Profile").getProperty("nick").createIndex(OClass.INDEX_TYPE.NOTUNIQUE);
  }

  @Test(dependsOnMethods = "testChangeOfIndexToNotUnique")
  public void testDuplicatedIndexOnNotUnique() {
    Profile nickNolte = new Profile("Jay", "Nick", "Nolte", null);
    database.save(nickNolte);
  }

  @Test(dependsOnMethods = "testDuplicatedIndexOnNotUnique")
  public void testQueryIndex() {
    List<?> result = database.query(new OSQLSynchQuery<Object>("select from index:Profile.nick where key = 'Jay'"));
    Assert.assertTrue(result.size() > 0);
  }

  @Test
  public void testIndexSQL() {
    database.command(new OCommandSQL("create index idx unique INTEGER METADATA { ignoreNullValues: false }")).execute();
    database.getMetadata().getIndexManager().reload();
    Assert.assertNotNull(database.getMetadata().getIndexManager().getIndex("idx"));

    final List<Long> positions = getValidPositions(3);

    database.command(new OCommandSQL("insert into index:idx (key,rid) values (10,#3:" + positions.get(0) + ')')).execute();
    database.command(new OCommandSQL("insert into index:idx (key,rid) values (20,#3:" + positions.get(1) + ')')).execute();

    List<ODocument> result = database.command(new OCommandSQL("select from index:idx")).execute();
    Assert.assertNotNull(result);
    Assert.assertEquals(result.size(), 2);
    for (ODocument d : result) {
      Assert.assertTrue(d.containsField("key"));
      Assert.assertTrue(d.containsField("rid"));

      if (d.field("key").equals(10))
        Assert.assertEquals(d.rawField("rid"), new ORecordId(3, positions.get(0)));
      else if (d.field("key").equals(20))
        Assert.assertEquals(d.rawField("rid"), new ORecordId(3, positions.get(1)));
      else
        Assert.assertTrue(false);
    }

    result = database.command(new OCommandSQL("select key, rid from index:idx")).execute();
    Assert.assertNotNull(result);
    Assert.assertEquals(result.size(), 2);
    for (ODocument d : result) {
      Assert.assertTrue(d.containsField("key"));
      Assert.assertTrue(d.containsField("rid"));

      if (d.field("key").equals(10))
        Assert.assertEquals(d.rawField("rid"), new ORecordId(3, positions.get(0)));
      else if (d.field("key").equals(20))
        Assert.assertEquals(d.rawField("rid"), new ORecordId(3, positions.get(1)));
      else
        Assert.assertTrue(false);
    }

    result = database.command(new OCommandSQL("select key from index:idx")).execute();
    Assert.assertNotNull(result);
    Assert.assertEquals(result.size(), 2);
    for (ODocument d : result) {
      Assert.assertTrue(d.containsField("key"));
      Assert.assertFalse(d.containsField("rid"));
    }

    result = database.command(new OCommandSQL("select rid from index:idx")).execute();
    Assert.assertNotNull(result);
    Assert.assertEquals(result.size(), 2);
    for (ODocument d : result) {
      Assert.assertFalse(d.containsField("key"));
      Assert.assertTrue(d.containsField("rid"));
    }

    result = database.command(new OCommandSQL("select rid from index:idx where key = 10")).execute();
    Assert.assertNotNull(result);
    Assert.assertEquals(result.size(), 1);
    for (ODocument d : result) {
      Assert.assertFalse(d.containsField("key"));
      Assert.assertTrue(d.containsField("rid"));
    }
  }

  @Test(dependsOnMethods = "testQueryIndex")
  public void testChangeOfIndexToUnique() {
    try {
      database.getMetadata().getSchema().getClass("Profile").getProperty("nick").dropIndexes();
      database.getMetadata().getSchema().getClass("Profile").getProperty("nick").createIndex(OClass.INDEX_TYPE.UNIQUE);
      Assert.assertTrue(false);
    } catch (ORecordDuplicatedException e) {
      Assert.assertTrue(true);
    }
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInMajorSelect() {
    if (database.getStorage() instanceof OStorageProxy) {
      return;
    }

    final boolean oldRecording = Orient.instance().getProfiler().isRecording();

    if (!oldRecording) {
      Orient.instance().getProfiler().startRecording();
    }

    long indexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    if (indexQueries < 0) {
      indexQueries = 0;
    }

    final List<Profile> result = database
        .command(new OSQLSynchQuery<Profile>("select * from Profile where nick > 'ZZZJayLongNickIndex3'")).execute();
    final List<String> expectedNicks = new ArrayList<String>(Arrays.asList("ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));

    if (!oldRecording) {
      Orient.instance().getProfiler().stopRecording();
    }

    Assert.assertEquals(result.size(), 2);
    for (Profile profile : result) {
      expectedNicks.remove(profile.getNick());
    }

    Assert.assertEquals(expectedNicks.size(), 0);
    long newIndexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    Assert.assertEquals(newIndexQueries, indexQueries + 1);
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInMajorEqualsSelect() {
    if (database.getStorage() instanceof OStorageProxy) {
      return;
    }

    final boolean oldRecording = Orient.instance().getProfiler().isRecording();

    if (!oldRecording) {
      Orient.instance().getProfiler().startRecording();
    }

    long indexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    if (indexQueries < 0) {
      indexQueries = 0;
    }

    final List<Profile> result = database
        .command(new OSQLSynchQuery<Profile>("select * from Profile where nick >= 'ZZZJayLongNickIndex3'")).execute();
    final List<String> expectedNicks = new ArrayList<String>(
        Arrays.asList("ZZZJayLongNickIndex3", "ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));

    if (!oldRecording) {
      Orient.instance().getProfiler().stopRecording();
    }

    Assert.assertEquals(result.size(), 3);
    for (Profile profile : result) {
      expectedNicks.remove(profile.getNick());
    }

    Assert.assertEquals(expectedNicks.size(), 0);
    long newIndexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    Assert.assertEquals(newIndexQueries, indexQueries + 1);
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInMinorSelect() {
    if (database.getStorage() instanceof OStorageProxy) {
      return;
    }

    final boolean oldRecording = Orient.instance().getProfiler().isRecording();

    if (!oldRecording) {
      Orient.instance().getProfiler().startRecording();
    }

    long indexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    if (indexQueries < 0) {
      indexQueries = 0;
    }

    final List<Profile> result = database.command(new OSQLSynchQuery<Profile>("select * from Profile where nick < '002'"))
        .execute();
    final List<String> expectedNicks = new ArrayList<String>(Arrays.asList("000", "001"));

    if (!oldRecording) {
      Orient.instance().getProfiler().stopRecording();
    }

    Assert.assertEquals(result.size(), 2);
    for (Profile profile : result) {
      expectedNicks.remove(profile.getNick());
    }

    Assert.assertEquals(expectedNicks.size(), 0);
    long newIndexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    Assert.assertEquals(newIndexQueries, indexQueries + 1);
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInMinorEqualsSelect() {
    if (database.getStorage() instanceof OStorageProxy) {
      return;
    }

    final boolean oldRecording = Orient.instance().getProfiler().isRecording();

    if (!oldRecording) {
      Orient.instance().getProfiler().startRecording();
    }

    long indexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    if (indexQueries < 0) {
      indexQueries = 0;
    }

    final List<Profile> result = database.command(new OSQLSynchQuery<Profile>("select * from Profile where nick <= '002'"))
        .execute();
    final List<String> expectedNicks = new ArrayList<String>(Arrays.asList("000", "001", "002"));

    if (!oldRecording) {
      Orient.instance().getProfiler().stopRecording();
    }

    Assert.assertEquals(result.size(), 3);
    for (Profile profile : result) {
      expectedNicks.remove(profile.getNick());
    }

    Assert.assertEquals(expectedNicks.size(), 0);
    long newIndexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    Assert.assertEquals(newIndexQueries, indexQueries + 1);
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexBetweenSelect() {
    if (database.getStorage() instanceof OStorageProxy) {
      return;
    }

    final boolean oldRecording = Orient.instance().getProfiler().isRecording();

    if (!oldRecording) {
      Orient.instance().getProfiler().startRecording();
    }

    long indexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    if (indexQueries < 0) {
      indexQueries = 0;
    }

    final List<Profile> result = database
        .command(new OSQLSynchQuery<Profile>("select * from Profile where nick between '001' and '004'")).execute();
    final List<String> expectedNicks = new ArrayList<String>(Arrays.asList("001", "002", "003", "004"));

    if (!oldRecording) {
      Orient.instance().getProfiler().stopRecording();
    }

    Assert.assertEquals(result.size(), 4);
    for (Profile profile : result) {
      expectedNicks.remove(profile.getNick());
    }

    Assert.assertEquals(expectedNicks.size(), 0);
    long newIndexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    Assert.assertEquals(newIndexQueries, indexQueries + 1);
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInComplexSelectOne() {
    if (database.getStorage() instanceof OStorageProxy) {
      return;
    }

    final boolean oldRecording = Orient.instance().getProfiler().isRecording();

    if (!oldRecording) {
      Orient.instance().getProfiler().startRecording();
    }

    long indexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    if (indexQueries < 0) {
      indexQueries = 0;
    }

    final List<Profile> result = database.command(new OSQLSynchQuery<Profile>(
        "select * from Profile where (name = 'Giuseppe' OR name <> 'Napoleone')"
            + " AND (nick is not null AND (name = 'Giuseppe' OR name <> 'Napoleone') AND (nick >= 'ZZZJayLongNickIndex3'))"))
        .execute();
    if (!oldRecording) {
      Orient.instance().getProfiler().stopRecording();
    }

    final List<String> expectedNicks = new ArrayList<String>(
        Arrays.asList("ZZZJayLongNickIndex3", "ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));
    Assert.assertEquals(result.size(), 3);
    for (Profile profile : result) {
      expectedNicks.remove(profile.getNick());
    }

    Assert.assertEquals(expectedNicks.size(), 0);
    long newIndexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    Assert.assertEquals(newIndexQueries, indexQueries + 1);
  }

  @Test(dependsOnMethods = "populateIndexDocuments")
  public void testIndexInComplexSelectTwo() {
    if (database.getStorage() instanceof OStorageProxy) {
      return;
    }

    final boolean oldRecording = Orient.instance().getProfiler().isRecording();

    if (!oldRecording) {
      Orient.instance().getProfiler().startRecording();
    }

    long indexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    if (indexQueries < 0) {
      indexQueries = 0;
    }

    final List<Profile> result = database.command(new OSQLSynchQuery<Profile>(
        "select * from Profile where " + "((name = 'Giuseppe' OR name <> 'Napoleone')"
            + " AND (nick is not null AND (name = 'Giuseppe' OR name <> 'Napoleone') AND (nick >= 'ZZZJayLongNickIndex3' OR nick >= 'ZZZJayLongNickIndex4')))"))
        .execute();
    if (!oldRecording) {
      Orient.instance().getProfiler().stopRecording();
    }

    final List<String> expectedNicks = new ArrayList<String>(
        Arrays.asList("ZZZJayLongNickIndex3", "ZZZJayLongNickIndex4", "ZZZJayLongNickIndex5"));
    Assert.assertEquals(result.size(), 3);
    for (Profile profile : result) {
      expectedNicks.remove(profile.getNick());
    }

    Assert.assertEquals(expectedNicks.size(), 0);
    long newIndexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
    Assert.assertEquals(newIndexQueries, indexQueries);
  }

  public void populateIndexDocuments() {
    for (int i = 0; i <= 5; i++) {
      final Profile profile = new Profile("ZZZJayLongNickIndex" + i, "NickIndex" + i, "NolteIndex" + i, null);
      database.save(profile);
    }

    for (int i = 0; i <= 5; i++) {
      final Profile profile = new Profile("00" + i, "NickIndex" + i, "NolteIndex" + i, null);
      database.save(profile);
    }
  }

  @Test(dependsOnMethods = "testChangeOfIndexToUnique")
  public void removeNotUniqueIndexOnNick() {
    database.getMetadata().getSchema().getClass("Profile").getProperty("nick").dropIndexes();
    database.getMetadata().getSchema().save();
  }

  @Test(dependsOnMethods = "removeNotUniqueIndexOnNick")
  public void testQueryingWithoutNickIndex() {
    Assert.assertTrue(database.getMetadata().getSchema().getClass("Profile").getProperty("name").isIndexed());
    Assert.assertTrue(!database.getMetadata().getSchema().getClass("Profile").getProperty("nick").isIndexed());

    List<Profile> result = database.command(new OSQLSynchQuery<ODocument>("SELECT FROM Profile WHERE nick = 'Jay'")).execute();
    Assert.assertEquals(result.size(), 2);

    result = database.command(new OSQLSynchQuery<ODocument>("SELECT FROM Profile WHERE nick = 'Jay' AND name = 'Jay'")).execute();
    Assert.assertEquals(result.size(), 1);

    result = database.command(new OSQLSynchQuery<ODocument>("SELECT FROM Profile WHERE nick = 'Jay' AND name = 'Nick'")).execute();
    Assert.assertEquals(result.size(), 1);
  }

  @Test(dependsOnMethods = "testQueryingWithoutNickIndex")
  public void createNotUniqueIndexOnNick() {
    database.getMetadata().getSchema().getClass("Profile").getProperty("nick").createIndex(OClass.INDEX_TYPE.NOTUNIQUE);
    database.getMetadata().getSchema().save();
  }

  @Test(dependsOnMethods = { "createNotUniqueIndexOnNick", "populateIndexDocuments" })
  public void testIndexInNotUniqueIndex() {
    final OProperty nickProperty = database.getMetadata().getSchema().getClass("Profile").getProperty("nick");
    Assert.assertEquals(nickProperty.getIndexes().iterator().next().getType(), OClass.INDEX_TYPE.NOTUNIQUE.toString());

    final boolean localStorage = !(database.getStorage() instanceof OStorageProxy);

    boolean oldRecording = true;
    long indexQueries = 0L;
    if (localStorage) {
      oldRecording = Orient.instance().getProfiler().isRecording();

      if (!oldRecording) {
        Orient.instance().getProfiler().startRecording();
      }

      indexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
      if (indexQueries < 0) {
        indexQueries = 0;
      }
    }

    final List<Profile> result = database.command(new OSQLSynchQuery<Profile>(
        "SELECT * FROM Profile WHERE nick in ['ZZZJayLongNickIndex0' ,'ZZZJayLongNickIndex1', 'ZZZJayLongNickIndex2']")).execute();

    final List<String> expectedSurnames = new ArrayList<String>(Arrays.asList("NolteIndex0", "NolteIndex1", "NolteIndex2"));

    if (localStorage && !oldRecording) {
      Orient.instance().getProfiler().stopRecording();
    }

    Assert.assertEquals(result.size(), 3);
    for (final Profile profile : result) {
      expectedSurnames.remove(profile.getSurname());
    }

    Assert.assertEquals(expectedSurnames.size(), 0);

    if (localStorage) {
      final long newIndexQueries = Orient.instance().getProfiler().getCounter("db.demo.query.indexUsed");
      Assert.assertEquals(newIndexQueries, indexQueries + 1);
    }
  }

  @Test
  public void testIndexCount() {
    final OIndex<?> nickIndex = database.getMetadata().getIndexManager().getIndex("Profile.nick");
    final List<ODocument> result = database.query(new OSQLSynchQuery<Object>("select count(*) from index:Profile.nick"));
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(result.get(0).<Long>field("count").longValue(), nickIndex.getSize());
  }

  public void indexLinks() {
    database.getMetadata().getSchema().getClass("Whiz").getProperty("account").createIndex(OClass.INDEX_TYPE.NOTUNIQUE);

    final List<Account> result = database.command(new OSQLSynchQuery<Account>("select * from Account limit 1")).execute();

    final OIndex<?> idx = database.getMetadata().getIndexManager().getIndex("Whiz.account");

    for (int i = 0; i < 5; i++) {
      final ODocument whiz = new ODocument("Whiz");

      whiz.field("id", i);
      whiz.field("text", "This is a test");
      whiz.field("account", result.get(0).getRid());

      whiz.save();
    }

    Assert.assertEquals(idx.getSize(), 5);

    final List<ODocument> indexedResult = database.getUnderlying()
        .command(new OSQLSynchQuery<Profile>("select * from Whiz where account = ?")).execute(result.get(0).getRid());

    Assert.assertEquals(indexedResult.size(), 5);

    for (final ODocument resDoc : indexedResult) {
      resDoc.delete();
    }

    final ODocument whiz = new ODocument("Whiz");
    whiz.field("id", 100);
    whiz.field("text", "This is a test!");
    whiz.field("account", new ODocument("Company").field("id", 9999));
    whiz.save();

    Assert.assertTrue(((ODocument) whiz.field("account")).getIdentity().isValid());

    ((ODocument) whiz.field("account")).delete();
    whiz.delete();
  }

  public void linkedIndexedProperty() {
    ODatabaseDocument db = new ODatabaseDocumentTx(database.getURL());
    db.open("admin", "admin");

    if (!db.getMetadata().getSchema().existsClass("TestClass")) {
      OClass testClass = db.getMetadata().getSchema().createClass("TestClass", 1, null);
      OClass testLinkClass = db.getMetadata().getSchema().createClass("TestLinkClass", 1, null);
      testClass.createProperty("testLink", OType.LINK, testLinkClass).createIndex(OClass.INDEX_TYPE.NOTUNIQUE);
      testClass.createProperty("name", OType.STRING).createIndex(OClass.INDEX_TYPE.UNIQUE);
      testLinkClass.createProperty("testBoolean", OType.BOOLEAN);
      testLinkClass.createProperty("testString", OType.STRING);
      db.getMetadata().getSchema().save();
    }
    ODocument testClassDocument = db.newInstance("TestClass");
    testClassDocument.field("name", "Test Class 1");
    ODocument testLinkClassDocument = new ODocument("TestLinkClass");
    testLinkClassDocument.field("testString", "Test Link Class 1");
    testLinkClassDocument.field("testBoolean", true);
    testClassDocument.field("testLink", testLinkClassDocument);
    testClassDocument.save();
    // THIS WILL THROW A java.lang.ClassCastException: com.orientechnologies.orient.core.id.ORecordId cannot be cast to
    // java.lang.Boolean
    List<ODocument> result = db.query(new OSQLSynchQuery<ODocument>("select from TestClass where testLink.testBoolean = true"));
    Assert.assertEquals(result.size(), 1);
    // THIS WILL THROW A java.lang.ClassCastException: com.orientechnologies.orient.core.id.ORecordId cannot be cast to
    // java.lang.String
    result = db.query(new OSQLSynchQuery<ODocument>("select from TestClass where testLink.testString = 'Test Link Class 1'"));
    Assert.assertEquals(result.size(), 1);

    db.close();
  }

  @Test(dependsOnMethods = "linkedIndexedProperty")
  public void testLinkedIndexedPropertyInTx() {
    ODatabaseDocument db = new ODatabaseDocumentTx(database.getURL());
    db.open("admin", "admin");

    db.begin();
    ODocument testClassDocument = db.newInstance("TestClass");
    testClassDocument.field("name", "Test Class 2");
    ODocument testLinkClassDocument = new ODocument("TestLinkClass");
    testLinkClassDocument.field("testString", "Test Link Class 2");
    testLinkClassDocument.field("testBoolean", true);
    testClassDocument.field("testLink", testLinkClassDocument);
    testClassDocument.save();
    db.commit();

    // THIS WILL THROW A java.lang.ClassCastException: com.orientechnologies.orient.core.id.ORecordId cannot be cast to
    // java.lang.Boolean
    List<ODocument> result = db.query(new OSQLSynchQuery<ODocument>("select from TestClass where testLink.testBoolean = true"));
    Assert.assertEquals(result.size(), 2);
    // THIS WILL THROW A java.lang.ClassCastException: com.orientechnologies.orient.core.id.ORecordId cannot be cast to
    // java.lang.String
    result = db.query(new OSQLSynchQuery<ODocument>("select from TestClass where testLink.testString = 'Test Link Class 2'"));
    Assert.assertEquals(result.size(), 1);

    db.close();
  }

  public void testDictionary() {
    ODatabaseDocument db = new ODatabaseDocumentTx(database.getURL());
    db.open("admin", "admin");

    OClass pClass = db.getMetadata().getSchema().createClass("Person2", 1, null);
    pClass.createProperty("firstName", OType.STRING);
    pClass.createProperty("lastName", OType.STRING);
    pClass.createProperty("age", OType.INTEGER);
    pClass.createIndex("testIdx", INDEX_TYPE.DICTIONARY, "firstName", "lastName");

    ODocument person = new ODocument("Person2");
    person.field("firstName", "foo").field("lastName", "bar").save();

    person = new ODocument("Person2");
    person.field("firstName", "foo").field("lastName", "bar").field("age", 32).save();

    db.close();
  }

  public void testConcurrentRemoveDelete() {
    ODatabaseDocument db = new ODatabaseDocumentTx(database.getURL());
    db.open("admin", "admin");

    if (!db.getMetadata().getSchema().existsClass("MyFruit")) {
      OClass fruitClass = db.getMetadata().getSchema().createClass("MyFruit", 1, null);
      fruitClass.createProperty("name", OType.STRING);
      fruitClass.createProperty("color", OType.STRING);

      db.getMetadata().getSchema().getClass("MyFruit").getProperty("name").createIndex(OClass.INDEX_TYPE.UNIQUE);

      db.getMetadata().getSchema().getClass("MyFruit").getProperty("color").createIndex(OClass.INDEX_TYPE.NOTUNIQUE);

      db.getMetadata().getSchema().save();
    }

    long expectedIndexSize = 0;

    final int passCount = 10;
    final int chunkSize = getEnvironment() == DatabaseAbstractTest.ENV.DEV ? 10 : 1000;

    for (int pass = 0; pass < passCount; pass++) {
      List<ODocument> recordsToDelete = new ArrayList<ODocument>();
      db.begin();
      for (int i = 0; i < chunkSize; i++) {
        ODocument d = new ODocument("MyFruit").field("name", "ABC" + pass + 'K' + i).field("color", "FOO" + pass);
        d.save();
        if (i < chunkSize / 2) {
          recordsToDelete.add(d);
        }
      }
      db.commit();

      expectedIndexSize += chunkSize;
      Assert.assertEquals(db.getMetadata().getIndexManager().getClassIndex("MyFruit", "MyFruit.color").getSize(), expectedIndexSize,
          "After add");

      // do delete
      db.begin();
      for (final ODocument recordToDelete : recordsToDelete) {
        Assert.assertNotNull(db.delete(recordToDelete));
      }
      db.commit();

      expectedIndexSize -= recordsToDelete.size();
      Assert.assertEquals(db.getMetadata().getIndexManager().getClassIndex("MyFruit", "MyFruit.color").getSize(), expectedIndexSize,
          "After delete");
    }

    db.close();
  }

  public void testIndexParamsAutoConversion() {
    ODatabaseDocument db = new ODatabaseDocumentTx(database.getURL());
    db.open("admin", "admin");

    if (!db.getMetadata().getSchema().existsClass("IndexTestTerm")) {
      final OClass termClass = db.getMetadata().getSchema().createClass("IndexTestTerm", 1, null);
      termClass.createProperty("label", OType.STRING);
      termClass.createIndex("idxTerm", INDEX_TYPE.UNIQUE.toString(), null, new ODocument().fields("ignoreNullValues", true),
          new String[] { "label" });

      db.getMetadata().getSchema().save();
    }

    final ODocument doc = new ODocument("IndexTestTerm");
    doc.field("label", "42");
    doc.save();

    final ORecordId result = (ORecordId) db.getMetadata().getIndexManager().getIndex("idxTerm").get("42");
    Assert.assertNotNull(result);
    Assert.assertEquals(result.getIdentity(), doc.getIdentity());
  }

  public void testTransactionUniqueIndexTestOne() {
    ODatabaseDocumentTx db = new ODatabaseDocumentTx(database.getURL());
    db.open("admin", "admin");

    if (!db.getMetadata().getSchema().existsClass("TransactionUniqueIndexTest")) {
      final OClass termClass = db.getMetadata().getSchema().createClass("TransactionUniqueIndexTest", 1, null);
      termClass.createProperty("label", OType.STRING);
      termClass.createIndex("idxTransactionUniqueIndexTest", INDEX_TYPE.UNIQUE.toString(), null,
          new ODocument().fields("ignoreNullValues", true), new String[] { "label" });
      db.getMetadata().getSchema().save();
    }

    ODocument docOne = new ODocument("TransactionUniqueIndexTest");
    docOne.field("label", "A");
    docOne.save();

    final List<ODocument> resultBeforeCommit = db
        .query(new OSQLSynchQuery<ODocument>("select from index:idxTransactionUniqueIndexTest"));
    Assert.assertEquals(resultBeforeCommit.size(), 1);

    db.begin();
    try {
      ODocument docTwo = new ODocument("TransactionUniqueIndexTest");
      docTwo.field("label", "A");
      docTwo.save();

      db.commit();
      Assert.fail();
    } catch (ORecordDuplicatedException oie) {
    }

    final List<ODocument> resultAfterCommit = db
        .query(new OSQLSynchQuery<ODocument>("select from index:idxTransactionUniqueIndexTest"));
    Assert.assertEquals(resultAfterCommit.size(), 1);
  }

  @Test(dependsOnMethods = "testTransactionUniqueIndexTestOne")
  public void testTransactionUniqueIndexTestTwo() {
    ODatabaseDocumentTx db = new ODatabaseDocumentTx(database.getURL());
    db.open("admin", "admin");

    if (!db.getMetadata().getSchema().existsClass("TransactionUniqueIndexTest")) {
      final OClass termClass = db.getMetadata().getSchema().createClass("TransactionUniqueIndexTest", 1, null);
      termClass.createProperty("label", OType.STRING);
      termClass.createIndex("idxTransactionUniqueIndexTest", INDEX_TYPE.UNIQUE.toString(), null,
          new ODocument().fields("ignoreNullValues", true), new String[] { "label" });
      db.getMetadata().getSchema().save();
    }

    final List<ODocument> resultBeforeCommit = db
        .query(new OSQLSynchQuery<ODocument>("select from index:idxTransactionUniqueIndexTest"));
    Assert.assertEquals(resultBeforeCommit.size(), 1);

    db.begin();

    try {
      ODocument docOne = new ODocument("TransactionUniqueIndexTest");
      docOne.field("label", "B");
      docOne.save();

      ODocument docTwo = new ODocument("TransactionUniqueIndexTest");
      docTwo.field("label", "B");
      docTwo.save();

      db.commit();
      Assert.fail();
    } catch (ORecordDuplicatedException oie) {
      db.rollback();
    }

    final List<ODocument> resultAfterCommit = db
        .query(new OSQLSynchQuery<ODocument>("select from index:idxTransactionUniqueIndexTest"));
    Assert.assertEquals(resultAfterCommit.size(), 1);
  }

  public void testTransactionUniqueIndexTestWithDotNameOne() {
    ODatabaseDocumentTx db = new ODatabaseDocumentTx(database.getURL());
    db.open("admin", "admin");

    if (!db.getMetadata().getSchema().existsClass("TransactionUniqueIndexWithDotTest")) {
      final OClass termClass = db.getMetadata().getSchema().createClass("TransactionUniqueIndexWithDotTest", 1, null);
      termClass.createProperty("label", OType.STRING).createIndex(INDEX_TYPE.UNIQUE);
      db.getMetadata().getSchema().save();
    }

    ODocument docOne = new ODocument("TransactionUniqueIndexWithDotTest");
    docOne.field("label", "A");
    docOne.save();

    final List<ODocument> resultBeforeCommit = db
        .query(new OSQLSynchQuery<ODocument>("select from  index:TransactionUniqueIndexWithDotTest.label"));
    Assert.assertEquals(resultBeforeCommit.size(), 1);

    long countClassBefore = db.countClass("TransactionUniqueIndexWithDotTest");
    db.begin();
    try {
      ODocument docTwo = new ODocument("TransactionUniqueIndexWithDotTest");
      docTwo.field("label", "A");
      docTwo.save();

      db.commit();
      Assert.fail();
    } catch (ORecordDuplicatedException oie) {
    }

    Assert.assertEquals(
        ((List<ODocument>) db.command(new OCommandSQL("select from TransactionUniqueIndexWithDotTest")).execute()).size(),
        countClassBefore);

    final List<ODocument> resultAfterCommit = db
        .query(new OSQLSynchQuery<ODocument>("select from  index:TransactionUniqueIndexWithDotTest.label"));
    Assert.assertEquals(resultAfterCommit.size(), 1);
  }

  @Test(dependsOnMethods = "testTransactionUniqueIndexTestWithDotNameOne")
  public void testTransactionUniqueIndexTestWithDotNameTwo() {
    ODatabaseDocumentTx db = new ODatabaseDocumentTx(database.getURL());
    db.open("admin", "admin");

    if (!db.getMetadata().getSchema().existsClass("TransactionUniqueIndexWithDotTest")) {
      final OClass termClass = db.getMetadata().getSchema().createClass("TransactionUniqueIndexWithDotTest", 1, null);
      termClass.createProperty("label", OType.STRING).createIndex(INDEX_TYPE.UNIQUE);
      db.getMetadata().getSchema().save();
    }

    final List<ODocument> resultBeforeCommit = db
        .query(new OSQLSynchQuery<ODocument>("select from index:TransactionUniqueIndexWithDotTest.label"));
    Assert.assertEquals(resultBeforeCommit.size(), 1);

    db.begin();

    try {
      ODocument docOne = new ODocument("TransactionUniqueIndexWithDotTest");
      docOne.field("label", "B");
      docOne.save();

      ODocument docTwo = new ODocument("TransactionUniqueIndexWithDotTest");
      docTwo.field("label", "B");
      docTwo.save();

      db.commit();
      Assert.fail();
    } catch (ORecordDuplicatedException oie) {
      db.rollback();
    }

    final List<ODocument> resultAfterCommit = db
        .query(new OSQLSynchQuery<ODocument>("select from  index:TransactionUniqueIndexWithDotTest.label"));
    Assert.assertEquals(resultAfterCommit.size(), 1);
  }

  @Test(dependsOnMethods = "linkedIndexedProperty")
  public void testIndexRemoval() {
    List<ODocument> result = database.command(new OCommandSQL("select rid from index:Profile.nick")).execute();
    Assert.assertNotNull(result);

    ODocument firstProfile = null;

    for (ODocument d : result) {
      if (firstProfile == null)
        firstProfile = d.field("rid");

      Assert.assertFalse(d.containsField("key"));
      Assert.assertTrue(d.containsField("rid"));
    }

    result = database.command(new OCommandSQL("select rid from index:Profile.nick where key = ?"))
        .execute(firstProfile.<Object>field("nick"));

    Assert.assertNotNull(result);
    Assert.assertEquals(result.get(0).field("rid"), firstProfile.getIdentity());

    firstProfile.delete();

    result = database.command(new OCommandSQL("select rid from index:Profile.nick where key = ?"))
        .execute(firstProfile.<Object>field("nick"));
    Assert.assertTrue(result.isEmpty());

  }

  public void createInheritanceIndex() {
    ODatabaseDocument db = new ODatabaseDocumentTx(database.getURL());
    try {
      db.open("admin", "admin");

      if (!db.getMetadata().getSchema().existsClass("BaseTestClass")) {
        OClass baseClass = db.getMetadata().getSchema().createClass("BaseTestClass", 1, null);
        OClass childClass = db.getMetadata().getSchema().createClass("ChildTestClass", 1, null);
        OClass anotherChildClass = db.getMetadata().getSchema().createClass("AnotherChildTestClass", 1, null);

        if (!baseClass.isSuperClassOf(childClass))
          childClass.setSuperClass(baseClass);
        if (!baseClass.isSuperClassOf(anotherChildClass))
          anotherChildClass.setSuperClass(baseClass);

        baseClass.createProperty("testParentProperty", OType.LONG).createIndex(OClass.INDEX_TYPE.NOTUNIQUE);

        db.getMetadata().getSchema().save();
      }

      ODocument childClassDocument = db.newInstance("ChildTestClass");
      childClassDocument.field("testParentProperty", 10L);
      childClassDocument.save();

      ODocument anotherChildClassDocument = db.newInstance("AnotherChildTestClass");
      anotherChildClassDocument.field("testParentProperty", 11L);
      anotherChildClassDocument.save();

      Assert.assertFalse(new ORecordId(-1, ORID.CLUSTER_POS_INVALID).equals(childClassDocument.getIdentity()));
      Assert.assertFalse(new ORecordId(-1, ORID.CLUSTER_POS_INVALID).equals(anotherChildClassDocument.getIdentity()));
    } finally {
      db.close();
    }
  }

  @Test(dependsOnMethods = "createInheritanceIndex")
  public void testIndexReturnOnlySpecifiedClass() throws Exception {
    List<ODocument> result;

    ODatabaseDocument db = database.getUnderlying();

    result = db.command(new OSQLSynchQuery("select * from ChildTestClass where testParentProperty = 10")).execute();
    Assert.assertNotNull(result);
    Assert.assertEquals(1, result.size());
    Assert.assertEquals(10L, result.get(0).<Object>field("testParentProperty"));

    result = db.command(new OCommandSQL("select * from AnotherChildTestClass where testParentProperty = 11")).execute();
    Assert.assertNotNull(result);
    Assert.assertEquals(1, result.size());
    Assert.assertEquals(11L, result.get(0).<Object>field("testParentProperty"));
  }

  @Test
  public void testManualIndexInTx() {
    if (database.getURL().startsWith("remote:"))
      return;

    ODatabaseDocumentTx db = (ODatabaseDocumentTx) database.getUnderlying();

    database.getMetadata().getSchema().createClass("ManualIndexTxClass", 1, null);

    OIndexManager idxManager = db.getMetadata().getIndexManager();
    OIndexFactory indexFactory = OIndexes.getFactory("UNIQUE", null);

    idxManager
        .createIndex("manualTxIndexTest", "UNIQUE", new OSimpleKeyIndexDefinition(indexFactory.getLastVersion(), OType.INTEGER),
            null, null, null);
    OIndex<OIdentifiable> idx = (OIndex<OIdentifiable>) idxManager.getIndex("manualTxIndexTest");

    ODocument v0 = new ODocument("ManualIndexTxClass");
    v0.field("counter", 0);
    v0.save();
    idx.put(0, v0);
    Assert.assertTrue(idx.contains(0));

    db.begin(OTransaction.TXTYPE.OPTIMISTIC);
    ODocument v = new ODocument("ManualIndexTxClass");
    v.field("counter", 52);
    v.save();

    ODocument v2 = new ODocument("ManualIndexTxClass");
    v2.field("counter", 54);
    v2.save();

    Assert.assertNotNull(idx);
    idx.remove(0);
    idx.put(52, v);

    db.commit();

    Assert.assertTrue(idx.contains(52));
    Assert.assertFalse(idx.contains(0));
    Assert.assertTrue(idx.get(52).getIdentity().isPersistent());
    Assert.assertEquals(idx.get(52).getIdentity(), v.getIdentity());
  }

  @Test
  public void testManualIndexInTxRecursiveStore() {
    if (database.getURL().startsWith("remote:"))
      return;

    ODatabaseDocumentTx db = (ODatabaseDocumentTx) database.getUnderlying();

    database.getMetadata().getSchema().createClass("ManualIndexTxRecursiveStoreClass", 1, null);

    OIndexManager idxManager = db.getMetadata().getIndexManager();
    OIndexFactory factory = OIndexes.getFactory("UNIQUE", null);

    idxManager.createIndex("manualTxIndexRecursiveStoreTest", "UNIQUE",
        new OSimpleKeyIndexDefinition(factory.getLastVersion(), OType.INTEGER), null, null, null);

    OIndex<OIdentifiable> idx = (OIndex<OIdentifiable>) idxManager.getIndex("manualTxIndexRecursiveStoreTest");

    ODocument v0 = new ODocument("ManualIndexTxRecursiveStoreClass");
    v0.field("counter", 0);
    v0.save();
    idx.put(0, v0);
    Assert.assertTrue(idx.contains(0));

    db.begin(OTransaction.TXTYPE.OPTIMISTIC);
    ODocument v = new ODocument("ManualIndexTxRecursiveStoreClass");
    v.field("counter", 52);

    ODocument v2 = new ODocument("ManualIndexTxRecursiveStoreClass");
    v2.field("counter", 54);
    v2.field("link", v);
    v2.save();

    v.field("link", v2);
    v.save();

    Assert.assertNotNull(idx);
    idx.remove(0);

    idx.put(52, v);
    idx.put(54, v2);

    db.commit();

    Assert.assertTrue(idx.contains(52));
    Assert.assertTrue(idx.contains(54));

    Assert.assertFalse(idx.contains(0));

    Assert.assertTrue(idx.get(52).getIdentity().isPersistent());
    Assert.assertEquals(idx.get(52).getIdentity(), v.getIdentity());

    Assert.assertTrue(idx.get(54).getIdentity().isPersistent());
    Assert.assertEquals(idx.get(54).getIdentity(), v2.getIdentity());
  }

  public void testIndexCountPlusCondition() {
    OIndexManager idxManager = database.getMetadata().getIndexManager();
    OIndexFactory factory = OIndexes.getFactory("NOTUNIQUE", null);
    idxManager
        .createIndex("IndexCountPlusCondition", "NOTUNIQUE", new OSimpleKeyIndexDefinition(factory.getLastVersion(), OType.INTEGER),
            null, null, null);

    final OIndex<OIdentifiable> idx = (OIndex<OIdentifiable>) idxManager.getIndex("IndexCountPlusCondition");

    final Map<Integer, Long> keyDocsCount = new HashMap<Integer, Long>();
    for (int i = 1; i < 100; i++) {
      final Integer key = (int) Math.log(i);

      final ODocument doc = new ODocument();
      doc.save();

      idx.put(key, doc);

      if (keyDocsCount.containsKey(key))
        keyDocsCount.put(key, keyDocsCount.get(key) + 1);
      else
        keyDocsCount.put(key, 1L);
    }

    for (Map.Entry<Integer, Long> entry : keyDocsCount.entrySet()) {
      List<ODocument> result = database
          .query(new OSQLSynchQuery<ODocument>("select count(*) from index:IndexCountPlusCondition where key = ?"), entry.getKey());
      Assert.assertEquals(result.get(0).<Long>field("count"), entry.getValue());
    }
  }

  public void testNotUniqueIndexKeySize() {
    OIndexManager idxManager = database.getMetadata().getIndexManager();
    idxManager
        .createIndex("IndexNotUniqueIndexKeySize", "NOTUNIQUE", new OSimpleKeyIndexDefinition(-1, OType.INTEGER), null, null, null);

    final OIndex<OIdentifiable> idx = (OIndex<OIdentifiable>) idxManager.getIndex("IndexNotUniqueIndexKeySize");

    final Set<Integer> keys = new HashSet<Integer>();
    for (int i = 1; i < 100; i++) {
      final Integer key = (int) Math.log(i);

      final ODocument doc = new ODocument();
      doc.save();

      idx.put(key, doc);

      keys.add(key);
    }

    Assert.assertEquals(idx.getKeySize(), keys.size());
  }

  public void testNotUniqueIndexSize() {
    OIndexManager idxManager = database.getMetadata().getIndexManager();
    idxManager
        .createIndex("IndexNotUniqueIndexSize", "NOTUNIQUE", new OSimpleKeyIndexDefinition(-1, OType.INTEGER), null, null, null);

    final OIndex<OIdentifiable> idx = (OIndex<OIdentifiable>) idxManager.getIndex("IndexNotUniqueIndexSize");

    for (int i = 1; i < 100; i++) {
      final Integer key = (int) Math.log(i);

      final ODocument doc = new ODocument();
      doc.save();

      idx.put(key, doc);
    }

    Assert.assertEquals(idx.getSize(), 99);
  }

  @Test
  public void testIndexRebuildDuringNonProxiedObjectDelete() {
    Profile profile = new Profile("NonProxiedObjectToDelete", "NonProxiedObjectToDelete", "NonProxiedObjectToDelete", null);
    profile = database.save(profile);

    OIndexManager idxManager = database.getMetadata().getIndexManager();
    OIndex<?> nickIndex = idxManager.getIndex("Profile.nick");

    Assert.assertTrue(nickIndex.contains("NonProxiedObjectToDelete"));

    final Profile loadedProfile = database.load(new ORecordId(profile.getId()));
    database.delete(database.<Object>detach(loadedProfile, true));

    Assert.assertFalse(nickIndex.contains("NonProxiedObjectToDelete"));
  }

  @Test(dependsOnMethods = "testIndexRebuildDuringNonProxiedObjectDelete")
  public void testIndexRebuildDuringDetachAllNonProxiedObjectDelete() {
    Profile profile = new Profile("NonProxiedObjectToDelete", "NonProxiedObjectToDelete", "NonProxiedObjectToDelete", null);
    profile = database.save(profile);

    OIndexManager idxManager = database.getMetadata().getIndexManager();
    OIndex<?> nickIndex = idxManager.getIndex("Profile.nick");

    Assert.assertTrue(nickIndex.contains("NonProxiedObjectToDelete"));

    final Profile loadedProfile = database.load(new ORecordId(profile.getId()));
    database.delete(database.<Object>detachAll(loadedProfile, true));

    Assert.assertFalse(nickIndex.contains("NonProxiedObjectToDelete"));
  }

  @Test(dependsOnMethods = "testIndexRebuildDuringDetachAllNonProxiedObjectDelete")
  public void testRestoreUniqueIndex() {
    database.getMetadata().getSchema().getClass("Profile").getProperty("nick").dropIndexes();
    database.command(new OCommandSQL("CREATE INDEX Profile.nick on Profile (nick) UNIQUE METADATA {ignoreNullValues: true}"))
        .execute();
    database.getMetadata().reload();
  }

  @Test
  public void testIndexInCompositeQuery() {
    OClass classOne = database.getMetadata().getSchema().createClass("CompoundSQLIndexTest1", 1, null);
    OClass classTwo = database.getMetadata().getSchema().createClass("CompoundSQLIndexTest2", 1, null);

    classTwo.createProperty("address", OType.LINK, classOne);

    classTwo.createIndex("CompoundSQLIndexTestIndex", INDEX_TYPE.UNIQUE, "address");

    ODocument docOne = new ODocument("CompoundSQLIndexTest1");
    docOne.field("city", "Montreal");

    docOne.save();

    ODocument docTwo = new ODocument("CompoundSQLIndexTest2");
    docTwo.field("address", docOne);
    docTwo.save();

    List<ODocument> result = database.getUnderlying().query(new OSQLSynchQuery<ODocument>(
        "select from CompoundSQLIndexTest2 where address in (select from CompoundSQLIndexTest1 where city='Montreal')"));
    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(result.get(0).getIdentity(), docTwo.getIdentity());
  }

  public void testIndexWithLimitAndOffset() {
    ODatabaseDocumentTx databaseDocumentTx = (ODatabaseDocumentTx) database.getUnderlying();

    final OSchema schema = databaseDocumentTx.getMetadata().getSchema();
    final OClass indexWithLimitAndOffset = schema.createClass("IndexWithLimitAndOffsetClass", 1, null);
    indexWithLimitAndOffset.createProperty("val", OType.INTEGER);
    indexWithLimitAndOffset.createProperty("index", OType.INTEGER);

    databaseDocumentTx
        .command(new OCommandSQL("create index IndexWithLimitAndOffset on IndexWithLimitAndOffsetClass (val) notunique"));

    for (int i = 0; i < 30; i++) {
      final ODocument document = new ODocument("IndexWithLimitAndOffsetClass");
      document.field("val", i / 10);
      document.field("index", i);
      document.save();
    }

    final List<ODocument> result = databaseDocumentTx
        .query(new OSQLSynchQuery<ODocument>("select from IndexWithLimitAndOffsetClass where val = 1 offset 5 limit 2"));
    Assert.assertEquals(result.size(), 2);

    for (int i = 0; i < 2; i++) {
      final ODocument document = result.get(i);
      Assert.assertEquals(document.<Object>field("val"), 1);
      Assert.assertEquals(document.<Object>field("index"), 15 + i);
    }
  }

  public void testIndexPaginationTest() {
    ODatabaseDocumentTx databaseDocumentTx = (ODatabaseDocumentTx) database.getUnderlying();

    final OSchema schema = databaseDocumentTx.getMetadata().getSchema();
    final OClass indexPaginationTest = schema.createClass("IndexPaginationTestClass", 1, null);
    indexPaginationTest.createProperty("prop", OType.INTEGER);
    indexPaginationTest.createIndex("IndexPaginationTest", INDEX_TYPE.UNIQUE, "prop", "@rid");

    List<ORID> rids = new ArrayList<ORID>();

    for (int i = 99; i >= 0; i--) {
      final ODocument document = new ODocument("IndexPaginationTestClass");
      document.field("prop", i / 2);
      document.save();

      rids.add(document.getIdentity());
    }

    List<ODocument> result = databaseDocumentTx
        .query(new OSQLSynchQuery<ODocument>("select from index:IndexPaginationTest order by key limit 5"));

    Assert.assertEquals(result.size(), 5);

    int lastKey = -1;
    ORID lastRid = null;
    for (ODocument document : result) {
      document.setLazyLoad(false);
      if (lastKey > -1)
        Assert.assertTrue(lastKey <= (Integer) document.<OCompositeKey>field("key").getKeys().get(0));

      lastKey = (Integer) document.<OCompositeKey>field("key").getKeys().get(0);
      lastRid = document.field("rid");

      Assert.assertTrue(rids.remove(document.<OIdentifiable>field("rid").getIdentity()));
    }

    while (true) {
      result = databaseDocumentTx
          .query(new OSQLSynchQuery<ODocument>("select from index:IndexPaginationTest where key > ? order by key limit 5"),
              new OCompositeKey(lastKey, lastRid));
      if (result.isEmpty())
        break;

      Assert.assertEquals(result.size(), 5);

      for (ODocument document : result) {
        document.setLazyLoad(false);
        if (lastKey > -1)
          Assert.assertTrue(lastKey <= (Integer) document.<OCompositeKey>field("key").getKeys().get(0));

        lastKey = (Integer) document.<OCompositeKey>field("key").getKeys().get(0);
        lastRid = document.field("rid", OType.LINK);

        Assert.assertTrue(rids.remove(document.<ORID>field("rid", OType.LINK)));
      }
    }

    Assert.assertTrue(rids.isEmpty());
  }

  public void testIndexPaginationTestDescOrder() {
    ODatabaseDocumentTx databaseDocumentTx = (ODatabaseDocumentTx) database.getUnderlying();

    final OSchema schema = databaseDocumentTx.getMetadata().getSchema();
    final OClass indexPaginationTest = schema.createClass("IndexPaginationTestDescOrderClass", 1, null);
    indexPaginationTest.createProperty("prop", OType.INTEGER);
    indexPaginationTest.createIndex("IndexPaginationTestDescOrder", INDEX_TYPE.UNIQUE, "prop", "@rid");

    List<ORID> rids = new ArrayList<ORID>();

    for (int i = 99; i >= 0; i--) {
      final ODocument document = new ODocument("IndexPaginationTestDescOrderClass");
      document.field("prop", i / 2);
      document.save();

      rids.add(document.getIdentity());
    }

    List<ODocument> result = databaseDocumentTx
        .query(new OSQLSynchQuery<ODocument>("select from index:IndexPaginationTestDescOrder order by key desc limit 5"));

    Assert.assertEquals(result.size(), 5);

    int lastKey = -1;
    ORID lastRid = null;
    for (ODocument document : result) {
      document.setLazyLoad(false);
      if (lastKey > -1)
        Assert.assertTrue(lastKey >= (Integer) document.<OCompositeKey>field("key").getKeys().get(0));

      lastKey = (Integer) document.<OCompositeKey>field("key").getKeys().get(0);
      lastRid = document.field("rid");

      Assert.assertTrue(rids.remove(document.<ORID>field("rid")));
    }

    while (true) {
      result = databaseDocumentTx.query(
          new OSQLSynchQuery<ODocument>("select from index:IndexPaginationTestDescOrder where key < ? order by key desc limit 5"),
          new OCompositeKey(lastKey, lastRid));
      if (result.isEmpty())
        break;

      Assert.assertEquals(result.size(), 5);

      for (ODocument document : result) {
        document.setLazyLoad(false);
        if (lastKey > -1)
          Assert.assertTrue(lastKey >= (Integer) document.<OCompositeKey>field("key").getKeys().get(0));

        lastKey = (Integer) document.<OCompositeKey>field("key").getKeys().get(0);
        lastRid = document.field("rid", OType.LINK);

        Assert.assertTrue(rids.remove(document.<ORID>field("rid", OType.LINK)));
      }
    }

    Assert.assertTrue(rids.isEmpty());
  }

  public void testNullIndexKeysSupport() {
    final ODatabaseDocumentTx databaseDocumentTx = (ODatabaseDocumentTx) database.getUnderlying();

    final OSchema schema = databaseDocumentTx.getMetadata().getSchema();
    final OClass clazz = schema.createClass("NullIndexKeysSupport", 1, null);
    clazz.createProperty("nullField", OType.STRING);

    ODocument metadata = new ODocument();
    metadata.field("ignoreNullValues", false);

    clazz.createIndex("NullIndexKeysSupportIndex", INDEX_TYPE.NOTUNIQUE.toString(), null, metadata, new String[] { "nullField" });
    for (int i = 0; i < 20; i++) {
      if (i % 5 == 0) {
        ODocument document = new ODocument("NullIndexKeysSupport");
        document.field("nullField", (Object) null);
        document.save();
      } else {
        ODocument document = new ODocument("NullIndexKeysSupport");
        document.field("nullField", "val" + i);
        document.save();
      }
    }

    List<ODocument> result = databaseDocumentTx
        .query(new OSQLSynchQuery<ODocument>("select from NullIndexKeysSupport where nullField = 'val3'"));
    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(result.get(0).field("nullField"), "val3");

    final String query = "select from NullIndexKeysSupport where nullField is null";
    result = databaseDocumentTx.query(new OSQLSynchQuery<ODocument>("select from NullIndexKeysSupport where nullField is null"));

    Assert.assertEquals(result.size(), 4);
    for (ODocument document : result)
      Assert.assertNull(document.field("nullField"));

    final ODocument explain = databaseDocumentTx.command(new OCommandSQL("explain " + query)).execute();
    Assert.assertTrue(explain.<Set<String>>field("involvedIndexes").contains("NullIndexKeysSupportIndex"));
  }

  public void testNullHashIndexKeysSupport() {
    final ODatabaseDocumentTx databaseDocumentTx = (ODatabaseDocumentTx) database.getUnderlying();

    final OSchema schema = databaseDocumentTx.getMetadata().getSchema();
    final OClass clazz = schema.createClass("NullHashIndexKeysSupport", 1, null);
    clazz.createProperty("nullField", OType.STRING);

    ODocument metadata = new ODocument();
    metadata.field("ignoreNullValues", false);

    clazz.createIndex("NullHashIndexKeysSupportIndex", INDEX_TYPE.NOTUNIQUE.toString(), null, metadata,
        new String[] { "nullField" });
    for (int i = 0; i < 20; i++) {
      if (i % 5 == 0) {
        ODocument document = new ODocument("NullHashIndexKeysSupport");
        document.field("nullField", (Object) null);
        document.save();
      } else {
        ODocument document = new ODocument("NullHashIndexKeysSupport");
        document.field("nullField", "val" + i);
        document.save();
      }
    }

    List<ODocument> result = databaseDocumentTx
        .query(new OSQLSynchQuery<ODocument>("select from NullHashIndexKeysSupport where nullField = 'val3'"));
    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(result.get(0).field("nullField"), "val3");

    final String query = "select from NullHashIndexKeysSupport where nullField is null";
    result = databaseDocumentTx
        .query(new OSQLSynchQuery<ODocument>("select from NullHashIndexKeysSupport where nullField is null"));

    Assert.assertEquals(result.size(), 4);
    for (ODocument document : result)
      Assert.assertNull(document.field("nullField"));

    final ODocument explain = databaseDocumentTx.command(new OCommandSQL("explain " + query)).execute();
    Assert.assertTrue(explain.<Set<String>>field("involvedIndexes").contains("NullHashIndexKeysSupportIndex"));
  }

  public void testNullIndexKeysSupportInTx() {
    final ODatabaseDocumentTx databaseDocumentTx = (ODatabaseDocumentTx) database.getUnderlying();

    final OSchema schema = databaseDocumentTx.getMetadata().getSchema();
    final OClass clazz = schema.createClass("NullIndexKeysSupportInTx", 1, null);
    clazz.createProperty("nullField", OType.STRING);

    ODocument metadata = new ODocument();
    metadata.field("ignoreNullValues", false);

    clazz.createIndex("NullIndexKeysSupportInTxIndex", INDEX_TYPE.NOTUNIQUE.toString(), null, metadata,
        new String[] { "nullField" });

    database.begin();

    for (int i = 0; i < 20; i++) {
      if (i % 5 == 0) {
        ODocument document = new ODocument("NullIndexKeysSupportInTx");
        document.field("nullField", (Object) null);
        document.save();
      } else {
        ODocument document = new ODocument("NullIndexKeysSupportInTx");
        document.field("nullField", "val" + i);
        document.save();
      }
    }

    database.commit();

    List<ODocument> result = databaseDocumentTx
        .query(new OSQLSynchQuery<ODocument>("select from NullIndexKeysSupportInTx where nullField = 'val3'"));
    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(result.get(0).field("nullField"), "val3");

    final String query = "select from NullIndexKeysSupportInTx where nullField is null";
    result = databaseDocumentTx
        .query(new OSQLSynchQuery<ODocument>("select from NullIndexKeysSupportInTx where nullField is null"));

    Assert.assertEquals(result.size(), 4);
    for (ODocument document : result)
      Assert.assertNull(document.field("nullField"));

    final ODocument explain = databaseDocumentTx.command(new OCommandSQL("explain " + query)).execute();
    Assert.assertTrue(explain.<Set<String>>field("involvedIndexes").contains("NullIndexKeysSupportInTxIndex"));
  }

  public void testNullIndexKeysSupportInMiddleTx() {
    if (database.getURL().startsWith("remote:"))
      return;

    final ODatabaseDocumentTx databaseDocumentTx = (ODatabaseDocumentTx) database.getUnderlying();

    final OSchema schema = databaseDocumentTx.getMetadata().getSchema();
    final OClass clazz = schema.createClass("NullIndexKeysSupportInMiddleTx", 1, null);
    clazz.createProperty("nullField", OType.STRING);

    ODocument metadata = new ODocument();
    metadata.field("ignoreNullValues", false);

    clazz.createIndex("NullIndexKeysSupportInMiddleTxIndex", INDEX_TYPE.NOTUNIQUE.toString(), null, metadata,
        new String[] { "nullField" });

    database.begin();

    for (int i = 0; i < 20; i++) {
      if (i % 5 == 0) {
        ODocument document = new ODocument("NullIndexKeysSupportInMiddleTx");
        document.field("nullField", (Object) null);
        document.save();
      } else {
        ODocument document = new ODocument("NullIndexKeysSupportInMiddleTx");
        document.field("nullField", "val" + i);
        document.save();
      }
    }

    List<ODocument> result = databaseDocumentTx
        .query(new OSQLSynchQuery<ODocument>("select from NullIndexKeysSupportInMiddleTx where nullField = 'val3'"));
    Assert.assertEquals(result.size(), 1);

    Assert.assertEquals(result.get(0).field("nullField"), "val3");

    final String query = "select from NullIndexKeysSupportInMiddleTx where nullField is null";
    result = databaseDocumentTx
        .query(new OSQLSynchQuery<ODocument>("select from NullIndexKeysSupportInMiddleTx where nullField is null"));

    Assert.assertEquals(result.size(), 4);
    for (ODocument document : result)
      Assert.assertNull(document.field("nullField"));

    final ODocument explain = databaseDocumentTx.command(new OCommandSQL("explain " + query)).execute();
    Assert.assertTrue(explain.<Set<String>>field("involvedIndexes").contains("NullIndexKeysSupportInMiddleTxIndex"));

    database.commit();
  }

  public void testCreateIndexAbstractClass() {
    final ODatabaseDocumentTx databaseDocumentTx = (ODatabaseDocumentTx) database.getUnderlying();
    final OSchema schema = databaseDocumentTx.getMetadata().getSchema();

    OClass abstractClass = schema.createAbstractClass("TestCreateIndexAbstractClass");
    abstractClass.createProperty("value", OType.STRING).setMandatory(true).createIndex(INDEX_TYPE.UNIQUE);

    schema.createClass("TestCreateIndexAbstractClassChildOne", abstractClass);
    schema.createClass("TestCreateIndexAbstractClassChildTwo", abstractClass);

    ODocument docOne = new ODocument("TestCreateIndexAbstractClassChildOne");
    docOne.field("value", "val1");
    docOne.save();

    ODocument docTwo = new ODocument("TestCreateIndexAbstractClassChildTwo");
    docTwo.field("value", "val2");
    docTwo.save();

    final String queryOne = "select from TestCreateIndexAbstractClass where value = 'val1'";

    List<ODocument> resultOne = databaseDocumentTx.query(new OSQLSynchQuery<ODocument>(queryOne));
    Assert.assertEquals(resultOne.size(), 1);
    Assert.assertEquals((Object) resultOne.get(0), (Object) docOne);

    ODocument explain = databaseDocumentTx.command(new OCommandSQL("explain " + queryOne)).execute();
    Assert.assertTrue(explain.<Collection<String>>field("involvedIndexes").contains("TestCreateIndexAbstractClass.value"));

    final String queryTwo = "select from TestCreateIndexAbstractClass where value = 'val2'";

    List<ODocument> resultTwo = databaseDocumentTx.query(new OSQLSynchQuery<ODocument>(queryTwo));
    Assert.assertEquals(resultTwo.size(), 1);
    Assert.assertEquals((Object) resultTwo.get(0), (Object) docTwo);

    explain = databaseDocumentTx.command(new OCommandSQL("explain " + queryTwo)).execute();
    Assert.assertTrue(explain.<Collection<String>>field("involvedIndexes").contains("TestCreateIndexAbstractClass.value"));
  }

  public void testValuesContainerIsRemovedIfIndexIsRemoved() {
    if (database.getURL().startsWith("remote:"))
      return;

    final OSchema schema = database.getMetadata().getSchema();
    OClass clazz = schema.createClass("ValuesContainerIsRemovedIfIndexIsRemovedClass", 1, null);
    clazz.createProperty("val", OType.STRING);

    database.command(new OCommandSQL(
        "create index ValuesContainerIsRemovedIfIndexIsRemovedIndex on ValuesContainerIsRemovedIfIndexIsRemovedClass (val) notunique"))
        .execute();

    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < 100; j++) {
        ODocument document = new ODocument("ValuesContainerIsRemovedIfIndexIsRemovedClass");
        document.field("val", "value" + i);
        document.save();
      }
    }

    final OAbstractPaginatedStorage storageLocalAbstract = (OAbstractPaginatedStorage) database.getStorage();

    final OWriteCache writeCache = storageLocalAbstract.getWriteCache();
    Assert.assertTrue(writeCache.exists("ValuesContainerIsRemovedIfIndexIsRemovedIndex.irs"));
    database.command(new OCommandSQL("drop index ValuesContainerIsRemovedIfIndexIsRemovedIndex")).execute();
    Assert.assertTrue(!writeCache.exists("ValuesContainerIsRemovedIfIndexIsRemovedIndex.irs"));
  }

  public void testPreservingIdentityInIndexTx() {
    OrientGraph graph = new OrientGraph((ODatabaseDocumentTx) database.getUnderlying(), true);
    graph.setAutoScaleEdgeType(true);

    OrientVertexType fieldClass = graph.getVertexType("PreservingIdentityInIndexTxChild");
    if (fieldClass == null) {
      fieldClass = graph.createVertexType("PreservingIdentityInIndexTxChild");
      fieldClass.createProperty("name", OType.STRING);
      fieldClass.createProperty("in_field", OType.LINK);
      fieldClass.createIndex("nameParentIndex", OClass.INDEX_TYPE.NOTUNIQUE, "in_field", "name");
    }

    Vertex parent = graph.addVertex("class:PreservingIdentityInIndexTxParent");
    Vertex child = graph.addVertex("class:PreservingIdentityInIndexTxChild");
    parent.addEdge("preservingIdentityInIndexTxEdge", child);
    child.setProperty("name", "pokus");

    Vertex parent2 = graph.addVertex("class:PreservingIdentityInIndexTxParent");
    Vertex child2 = graph.addVertex("class:PreservingIdentityInIndexTxChild");
    parent2.addEdge("preservingIdentityInIndexTxEdge", child2);
    child2.setProperty("name", "pokus2");
    graph.commit();

    {
      fieldClass = graph.getVertexType("PreservingIdentityInIndexTxChild");
      OIndex<?> index = fieldClass.getClassIndex("nameParentIndex");
      OCompositeKey key = new OCompositeKey(parent.getId(), "pokus");

      Set<ORecordId> h = (Set<ORecordId>) index.get(key);
      for (ORecordId o : h) {
        Assert.assertNotNull(graph.getVertex(o));
      }
    }

    {
      fieldClass = graph.getVertexType("PreservingIdentityInIndexTxChild");
      OIndex<?> index = fieldClass.getClassIndex("nameParentIndex");
      OCompositeKey key = new OCompositeKey(parent2.getId(), "pokus2");

      Set<ORecordId> h = (Set<ORecordId>) index.get(key);
      for (ORecordId o : h) {
        Assert.assertNotNull(graph.getVertex(o));
      }
    }

    parent.remove();
    child.remove();

    parent2.remove();
    child2.remove();

    graph.shutdown();
  }

  public void testEmptyNotUniqueIndex() {
    OClass emptyNotUniqueIndexClazz = database.getMetadata().getSchema().createClass("EmptyNotUniqueIndexTest", 1, null);
    emptyNotUniqueIndexClazz.createProperty("prop", OType.STRING);

    final OIndex notUniqueIndex = emptyNotUniqueIndexClazz
        .createIndex("EmptyNotUniqueIndexTestIndex", INDEX_TYPE.NOTUNIQUE_HASH_INDEX, "prop");
    ODocument document = new ODocument("EmptyNotUniqueIndexTest");
    document.field("prop", "keyOne");
    document.save();

    document = new ODocument("EmptyNotUniqueIndexTest");
    document.field("prop", "keyTwo");
    document.save();

    Assert.assertFalse(notUniqueIndex.contains("RandomKeyOne"));
    Assert.assertTrue(notUniqueIndex.contains("keyOne"));

    Assert.assertFalse(notUniqueIndex.contains("RandomKeyTwo"));
    Assert.assertTrue(notUniqueIndex.contains("keyTwo"));
  }

  public void testNullIteration() {
    ODatabaseDocumentTx database = (ODatabaseDocumentTx) this.database.getUnderlying();
    OrientGraph graph = new OrientGraph(database, false);

    OClass v = database.getMetadata().getSchema().getClass("V");
    OClass testNullIteration = database.getMetadata().getSchema().createClass("NullIterationTest", v);
    testNullIteration.createProperty("name", OType.STRING);
    testNullIteration.createProperty("birth", OType.DATETIME);

    database.command(new OCommandSQL("CREATE VERTEX NullIterationTest SET name = 'Andrew', birth = sysdate()")).execute();
    database.command(new OCommandSQL("CREATE VERTEX NullIterationTest SET name = 'Marcel', birth = sysdate()")).execute();
    database.command(new OCommandSQL("CREATE VERTEX NullIterationTest SET name = 'Olivier'")).execute();

    ODocument metadata = new ODocument();
    metadata.field("ignoreNullValues", false);

    testNullIteration.createIndex("NullIterationTestIndex", INDEX_TYPE.NOTUNIQUE.name(), null, metadata, new String[] { "birth" });

    List<ODocument> result = database.query(new OSQLSynchQuery<ODocument>("SELECT FROM NullIterationTest ORDER BY birth ASC"));
    Assert.assertEquals(result.size(), 3);

    result = database.query(new OSQLSynchQuery<ODocument>("SELECT FROM NullIterationTest ORDER BY birth DESC"));
    Assert.assertEquals(result.size(), 3);

    result = database.query(new OSQLSynchQuery<ODocument>("SELECT FROM NullIterationTest"));
    Assert.assertEquals(result.size(), 3);
  }

  private List<Long> getValidPositions(int clusterId) {
    final List<Long> positions = new ArrayList<Long>();

    final ORecordIteratorCluster<?> iteratorCluster = database.getUnderlying()
        .browseCluster(database.getClusterNameById(clusterId));

    for (int i = 0; i < 7; i++) {
      if (!iteratorCluster.hasNext())
        break;

      ORecord doc = iteratorCluster.next();
      positions.add(doc.getIdentity().getClusterPosition());
    }
    return positions;
  }

  public void testMultikeyWithoutFieldAndNullSupport() {
    //generates stubs for index
    ODocument doc1 = new ODocument();
    doc1.save();
    ODocument doc2 = new ODocument();
    doc2.save();
    ODocument doc3 = new ODocument();
    doc3.save();
    ODocument doc4 = new ODocument();
    doc4.save();

    final ORID rid1 = doc1.getIdentity();
    final ORID rid2 = doc2.getIdentity();
    final ORID rid3 = doc3.getIdentity();
    final ORID rid4 = doc4.getIdentity();

    ODatabaseDocumentTx database = (ODatabaseDocumentTx) this.database.getUnderlying();

    final OSchema schema = database.getMetadata().getSchema();
    OClass clazz = schema.createClass("TestMultikeyWithoutField");

    clazz.createProperty("state", OType.BYTE);
    clazz.createProperty("users", OType.LINKSET);
    clazz.createProperty("time", OType.LONG);
    clazz.createProperty("reg", OType.LONG);
    clazz.createProperty("no", OType.INTEGER);

    final ODocument mt = new ODocument().field("ignoreNullValues", false);
    clazz.createIndex("MultikeyWithoutFieldIndex", INDEX_TYPE.UNIQUE.toString(), null, mt,
        new String[] { "state", "users", "time", "reg", "no" });

    ODocument document = new ODocument("TestMultikeyWithoutField");
    document.field("state", (byte) 1);

    Set<ORID> users = new HashSet<ORID>();
    users.add(rid1);
    users.add(rid2);

    document.field("users", users);
    document.field("time", 12L);
    document.field("reg", 14L);
    document.field("no", 12);

    document.save();

    OIndex index = database.getMetadata().getIndexManager().getIndex("MultikeyWithoutFieldIndex");
    Assert.assertEquals(index.getSize(), 2);

    //we support first and last keys check only for embedded storage
    if (!(database.getStorage() instanceof OStorageProxy)) {
      Assert.assertEquals(index.getFirstKey(), new OCompositeKey((byte) 1, rid1, 12L, 14L, 12));
      Assert.assertEquals(index.getLastKey(), new OCompositeKey((byte) 1, rid2, 12L, 14L, 12));
    }

    final ORID rid = document.getIdentity();

    database.close();
    database.open("admin", "admin");

    document = database.load(rid);

    users = document.field("users");
    users.remove(rid1);
    document.save();

    index = database.getMetadata().getIndexManager().getIndex("MultikeyWithoutFieldIndex");
    Assert.assertEquals(index.getSize(), 1);
    if (!(database.getStorage() instanceof OStorageProxy)) {
      Assert.assertEquals(index.getFirstKey(), new OCompositeKey((byte) 1, rid2, 12L, 14L, 12));
    }

    database.close();
    database.open("admin", "admin");

    document = database.load(rid);

    users = document.field("users");
    users.remove(rid2);
    Assert.assertTrue(users.isEmpty());
    document.save();

    index = database.getMetadata().getIndexManager().getIndex("MultikeyWithoutFieldIndex");

    Assert.assertEquals(index.getSize(), 1);
    if (!(database.getStorage() instanceof OStorageProxy)) {
      Assert.assertEquals(index.getFirstKey(), new OCompositeKey((byte) 1, null, 12L, 14L, 12));
    }

    database.close();
    database.open("admin", "admin");

    document = database.load(rid);
    users = document.field("users");
    users.add(rid3);
    document.save();

    index = database.getMetadata().getIndexManager().getIndex("MultikeyWithoutFieldIndex");

    Assert.assertEquals(index.getSize(), 1);
    if (!(database.getStorage() instanceof OStorageProxy)) {
      Assert.assertEquals(index.getFirstKey(), new OCompositeKey((byte) 1, rid3, 12L, 14L, 12));
    }

    database.close();
    database.open("admin", "admin");

    users = document.field("users");
    users.add(rid4);
    document.save();

    index = database.getMetadata().getIndexManager().getIndex("MultikeyWithoutFieldIndex");
    Assert.assertEquals(index.getSize(), 2);

    if (!(database.getStorage() instanceof OStorageProxy)) {
      Assert.assertEquals(index.getFirstKey(), new OCompositeKey((byte) 1, rid3, 12L, 14L, 12));
      Assert.assertEquals(index.getLastKey(), new OCompositeKey((byte) 1, rid4, 12L, 14L, 12));
    }

    database.close();
    database.open("admin", "admin");

    document.removeField("users");
    document.save();

    index = database.getMetadata().getIndexManager().getIndex("MultikeyWithoutFieldIndex");
    Assert.assertEquals(index.getSize(), 1);

    if (!(database.getStorage() instanceof OStorageProxy)) {
      Assert.assertEquals(index.getFirstKey(), new OCompositeKey((byte) 1, null, 12L, 14L, 12));
    }
  }

  public void testMultikeyWithoutFieldAndNoNullSupport() {
    //generates stubs for index
    ODocument doc1 = new ODocument();
    doc1.save();
    ODocument doc2 = new ODocument();
    doc2.save();
    ODocument doc3 = new ODocument();
    doc3.save();
    ODocument doc4 = new ODocument();
    doc4.save();

    final ORID rid1 = doc1.getIdentity();
    final ORID rid2 = doc2.getIdentity();
    final ORID rid3 = doc3.getIdentity();
    final ORID rid4 = doc4.getIdentity();

    ODatabaseDocumentTx database = (ODatabaseDocumentTx) this.database.getUnderlying();

    final OSchema schema = database.getMetadata().getSchema();
    OClass clazz = schema.createClass("TestMultikeyWithoutFieldNoNullSupport");

    clazz.createProperty("state", OType.BYTE);
    clazz.createProperty("users", OType.LINKSET);
    clazz.createProperty("time", OType.LONG);
    clazz.createProperty("reg", OType.LONG);
    clazz.createProperty("no", OType.INTEGER);

    clazz.createIndex("MultikeyWithoutFieldIndexNoNullSupport", INDEX_TYPE.UNIQUE.toString(), null,
        new ODocument().fields("ignoreNullValues", true), new String[] { "state", "users", "time", "reg", "no" });

    ODocument document = new ODocument("TestMultikeyWithoutFieldNoNullSupport");
    document.field("state", (byte) 1);

    Set<ORID> users = new HashSet<ORID>();
    users.add(rid1);
    users.add(rid2);

    document.field("users", users);
    document.field("time", 12L);
    document.field("reg", 14L);
    document.field("no", 12);

    document.save();

    OIndex index = database.getMetadata().getIndexManager().getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.getSize(), 2);

    //we support first and last keys check only for embedded storage
    if (!(database.getStorage() instanceof OStorageProxy)) {
      Assert.assertEquals(index.getFirstKey(), new OCompositeKey((byte) 1, rid1, 12L, 14L, 12));
      Assert.assertEquals(index.getLastKey(), new OCompositeKey((byte) 1, rid2, 12L, 14L, 12));
    }

    final ORID rid = document.getIdentity();

    database.close();
    database.open("admin", "admin");

    document = database.load(rid);

    users = document.field("users");
    users.remove(rid1);
    document.save();

    index = database.getMetadata().getIndexManager().getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.getSize(), 1);
    if (!(database.getStorage() instanceof OStorageProxy)) {
      Assert.assertEquals(index.getFirstKey(), new OCompositeKey((byte) 1, rid2, 12L, 14L, 12));
    }

    database.close();
    database.open("admin", "admin");

    document = database.load(rid);

    users = document.field("users");
    users.remove(rid2);
    Assert.assertTrue(users.isEmpty());

    document.save();

    index = database.getMetadata().getIndexManager().getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.getSize(), 0);

    database.close();
    database.open("admin", "admin");

    document = database.load(rid);
    users = document.field("users");
    users.add(rid3);
    document.save();

    index = database.getMetadata().getIndexManager().getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.getSize(), 1);

    if (!(database.getStorage() instanceof OStorageProxy)) {
      Assert.assertEquals(index.getFirstKey(), new OCompositeKey((byte) 1, rid3, 12L, 14L, 12));
    }

    database.close();
    database.open("admin", "admin");

    users = document.field("users");
    users.add(rid4);
    document.save();

    index = database.getMetadata().getIndexManager().getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.getSize(), 2);

    if (!(database.getStorage() instanceof OStorageProxy)) {
      Assert.assertEquals(index.getFirstKey(), new OCompositeKey((byte) 1, rid3, 12L, 14L, 12));
      Assert.assertEquals(index.getLastKey(), new OCompositeKey((byte) 1, rid4, 12L, 14L, 12));
    }

    database.close();
    database.open("admin", "admin");

    document.removeField("users");
    document.save();

    index = database.getMetadata().getIndexManager().getIndex("MultikeyWithoutFieldIndexNoNullSupport");
    Assert.assertEquals(index.getSize(), 0);
  }

  public void testIndexEdgeComposite() {
    OrientGraph graphNoTx = new OrientGraph((ODatabaseDocumentTx) database.getUnderlying());
    OrientVertexType vertexType = null;
    if (!graphNoTx.getRawGraph().existsCluster("CustomVertex")) {
      vertexType = graphNoTx.createVertexType("CustomVertex");
    } else {
      vertexType = graphNoTx.getVertexType("CustomVertex");
    }

    if (!graphNoTx.getRawGraph().existsCluster("CustomEdge")) {
      OrientEdgeType edgeType = graphNoTx.createEdgeType("CustomEdge");
      edgeType.createProperty("out", OType.LINK, vertexType);
      edgeType.createProperty("in", OType.LINK, vertexType);
      edgeType
          .createIndex("CustomEdge.in", OClass.INDEX_TYPE.UNIQUE.toString(), null, new ODocument().fields("ignoreNullValues", true),
              new String[] { "in" });
      edgeType.createIndex("CustomEdge.out", OClass.INDEX_TYPE.UNIQUE.toString(), null,
          new ODocument().fields("ignoreNullValues", true), new String[] { "out" });
      edgeType.createIndex("CustomEdge.compositeInOut", OClass.INDEX_TYPE.UNIQUE.toString(), null,
          new ODocument().fields("ignoreNullValues", true), new String[] { "out", "in" });
    }
    // graphNoTx.shutdown();

    OrientGraph graph = new OrientGraph((ODatabaseDocumentTx) database.getUnderlying());
    Vertex inVert = null;
    for (int i = 0; i < 5; ++i) {
      Vertex currentVert = graph.addVertex("class:CustomVertex");
      if (inVert != null) {
        graph.addEdge("class:CustomEdge", currentVert, inVert, "CustomEdge");
      }
      inVert = currentVert;
    }
    graph.commit();

    Iterable<Vertex> verts = graph.getVertices();
    StringBuilder vertIds = new StringBuilder();
    for (Vertex vert : verts) {
      vertIds.append(vert.getId().toString()).append(" ");
    }
    System.out.println("Vertices: " + vertIds);
    System.out.println();

    checkIndexKeys(graph, "CustomEdge.in");
    checkIndexKeys(graph, "CustomEdge.out");
    checkIndexKeys(graph, "CustomEdge.compositeInOut");
  }

  public void testNullValuesCountSBTreeUnique() {
    final ODatabaseDocumentTx db = (ODatabaseDocumentTx) database.getUnderlying();

    OClass nullSBTreeClass = db.getMetadata().getSchema().createClass("NullValuesCountSBTreeUnique");
    nullSBTreeClass.createProperty("field", OType.INTEGER);
    nullSBTreeClass.createIndex("NullValuesCountSBTreeUniqueIndex", INDEX_TYPE.UNIQUE, "field");

    ODocument docOne = new ODocument("NullValuesCountSBTreeUnique");
    docOne.field("field", 1);
    docOne.save();

    ODocument docTwo = new ODocument("NullValuesCountSBTreeUnique");
    docTwo.field("field", (Integer) null);
    docTwo.save();

    OIndex index = db.getMetadata().getIndexManager().getIndex("NullValuesCountSBTreeUniqueIndex");
    Assert.assertEquals(index.getSize(), 2);
    Assert.assertEquals(index.getKeySize(), 2);
  }

  public void testNullValuesCountSBTreeNotUniqueOne() {
    final ODatabaseDocumentTx db = (ODatabaseDocumentTx) database.getUnderlying();

    OClass nullSBTreeClass = db.getMetadata().getSchema().createClass("NullValuesCountSBTreeNotUniqueOne");
    nullSBTreeClass.createProperty("field", OType.INTEGER);
    nullSBTreeClass.createIndex("NullValuesCountSBTreeNotUniqueOneIndex", INDEX_TYPE.NOTUNIQUE, "field");

    ODocument docOne = new ODocument("NullValuesCountSBTreeNotUniqueOne");
    docOne.field("field", 1);
    docOne.save();

    ODocument docTwo = new ODocument("NullValuesCountSBTreeNotUniqueOne");
    docTwo.field("field", (Integer) null);
    docTwo.save();

    OIndex index = db.getMetadata().getIndexManager().getIndex("NullValuesCountSBTreeNotUniqueOneIndex");
    Assert.assertEquals(index.getSize(), 2);
    Assert.assertEquals(index.getKeySize(), 2);
  }

  public void testNullValuesCountSBTreeNotUniqueTwo() {
    final ODatabaseDocumentTx db = (ODatabaseDocumentTx) database.getUnderlying();

    OClass nullSBTreeClass = db.getMetadata().getSchema().createClass("NullValuesCountSBTreeNotUniqueTwo");
    nullSBTreeClass.createProperty("field", OType.INTEGER);
    nullSBTreeClass.createIndex("NullValuesCountSBTreeNotUniqueTwoIndex", INDEX_TYPE.NOTUNIQUE, "field");

    ODocument docOne = new ODocument("NullValuesCountSBTreeNotUniqueTwo");
    docOne.field("field", (Integer) null);
    docOne.save();

    ODocument docTwo = new ODocument("NullValuesCountSBTreeNotUniqueTwo");
    docTwo.field("field", (Integer) null);
    docTwo.save();

    OIndex index = db.getMetadata().getIndexManager().getIndex("NullValuesCountSBTreeNotUniqueTwoIndex");
    Assert.assertEquals(index.getKeySize(), 1);
    Assert.assertEquals(index.getSize(), 2);
  }

  public void testNullValuesCountHashUnique() {
    final ODatabaseDocumentTx db = (ODatabaseDocumentTx) database.getUnderlying();

    OClass nullSBTreeClass = db.getMetadata().getSchema().createClass("NullValuesCountHashUnique");
    nullSBTreeClass.createProperty("field", OType.INTEGER);
    nullSBTreeClass.createIndex("NullValuesCountHashUniqueIndex", INDEX_TYPE.UNIQUE_HASH_INDEX, "field");

    ODocument docOne = new ODocument("NullValuesCountHashUnique");
    docOne.field("field", 1);
    docOne.save();

    ODocument docTwo = new ODocument("NullValuesCountHashUnique");
    docTwo.field("field", (Integer) null);
    docTwo.save();

    OIndex index = db.getMetadata().getIndexManager().getIndex("NullValuesCountHashUniqueIndex");
    Assert.assertEquals(index.getSize(), 2);
    Assert.assertEquals(index.getKeySize(), 2);
  }

  public void testNullValuesCountHashNotUniqueOne() {
    final ODatabaseDocumentTx db = (ODatabaseDocumentTx) database.getUnderlying();

    OClass nullSBTreeClass = db.getMetadata().getSchema().createClass("NullValuesCountHashNotUniqueOne");
    nullSBTreeClass.createProperty("field", OType.INTEGER);
    nullSBTreeClass.createIndex("NullValuesCountHashNotUniqueOneIndex", INDEX_TYPE.NOTUNIQUE_HASH_INDEX, "field");

    ODocument docOne = new ODocument("NullValuesCountHashNotUniqueOne");
    docOne.field("field", 1);
    docOne.save();

    ODocument docTwo = new ODocument("NullValuesCountHashNotUniqueOne");
    docTwo.field("field", (Integer) null);
    docTwo.save();

    OIndex index = db.getMetadata().getIndexManager().getIndex("NullValuesCountHashNotUniqueOneIndex");
    Assert.assertEquals(index.getSize(), 2);
    Assert.assertEquals(index.getKeySize(), 2);
  }

  public void testNullValuesCountHashNotUniqueTwo() {
    final ODatabaseDocumentTx db = (ODatabaseDocumentTx) database.getUnderlying();

    OClass nullSBTreeClass = db.getMetadata().getSchema().createClass("NullValuesCountHashNotUniqueTwo");
    nullSBTreeClass.createProperty("field", OType.INTEGER);
    nullSBTreeClass.createIndex("NullValuesCountHashNotUniqueTwoIndex", INDEX_TYPE.NOTUNIQUE_HASH_INDEX, "field");

    ODocument docOne = new ODocument("NullValuesCountHashNotUniqueTwo");
    docOne.field("field", (Integer) null);
    docOne.save();

    ODocument docTwo = new ODocument("NullValuesCountHashNotUniqueTwo");
    docTwo.field("field", (Integer) null);
    docTwo.save();

    OIndex index = db.getMetadata().getIndexManager().getIndex("NullValuesCountHashNotUniqueTwoIndex");
    Assert.assertEquals(index.getKeySize(), 1);
    Assert.assertEquals(index.getSize(), 2);
  }

  @Test
  public void testParamsOrder() {

    OrientBaseGraph graph = new OrientGraphNoTx("memory:IndexTest_testParamsOrder", "admin", "admin");

    graph.command(new OCommandSQL("CREATE CLASS Task extends V")).execute();
    graph.command(new OCommandSQL("CREATE PROPERTY Task.projectId STRING (MANDATORY TRUE, NOTNULL, MAX 20)")).execute();
    graph.command(new OCommandSQL("CREATE PROPERTY Task.seq SHORT ( MANDATORY TRUE, NOTNULL, MIN 0)")).execute();
    graph.command(new OCommandSQL("CREATE INDEX TaskPK ON Task (projectId, seq) UNIQUE")).execute();

    graph.command(new OCommandSQL("INSERT INTO Task (projectId, seq) values ( 'foo', 2)")).execute();
    graph.command(new OCommandSQL("INSERT INTO Task (projectId, seq) values ( 'bar', 3)")).execute();
    Iterable<Vertex> x = graph.getVertices("Task", new String[] { "seq", "projectId" }, new Object[] { (short) 2, "foo" });
    Iterator<Vertex> iter = x.iterator();
    Assert.assertTrue(iter.hasNext());
    iter.next();
    Assert.assertFalse(iter.hasNext());
    graph.drop();
  }

  private static void checkIndexKeys(OrientGraph graph, String indexName) {
    Iterable<ODocument> indexDataDocs = (Iterable<ODocument>) graph.getRawGraph()
        .query(new OSQLSynchQuery<ODocument>("select from index:" + indexName));
    for (ODocument indexDataDoc : indexDataDocs) {
      Object key = indexDataDoc.field("key");
      if (key instanceof ORecordId) {
        Assert.assertTrue(((ORecordId) key).isPersistent());
      } else if (key instanceof List) {
        List<ORecordId> ids = (List<ORecordId>) key;
        for (ORecordId oRecordId : ids) {
          Assert.assertTrue(oRecordId.isPersistent());
        }
      }
    }
  }
}
