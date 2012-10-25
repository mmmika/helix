package org.apache.helix.manager.zk;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;

import org.apache.helix.DataAccessor;
import org.apache.helix.PropertyPathConfig;
import org.apache.helix.PropertyType;
import org.apache.helix.ZNRecord;
import org.apache.helix.ZkUnitTestBase;
import org.apache.helix.manager.zk.ZKDataAccessor;
import org.apache.helix.manager.zk.ZNRecordSerializer;
import org.apache.helix.manager.zk.ZkClient;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.IdealState.IdealStateModeProperty;
import org.apache.zookeeper.data.Stat;
import org.testng.Assert;
import org.testng.AssertJUnit;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;



public class TestZKDataAccessor extends ZkUnitTestBase
{
  private DataAccessor _accessor;
  private String _clusterName;
  private final String resource = "resource";
	private ZkClient _zkClient;

  @Test ()
  public void testSet()
  {
    IdealState idealState = new IdealState(resource);
    idealState.setNumPartitions(20);
    idealState.setReplicas(Integer.toString(2));
    idealState.setStateModelDefRef("StateModel1");
    idealState.setIdealStateMode(IdealStateModeProperty.AUTO.toString());
    boolean success = _accessor.setProperty(PropertyType.IDEALSTATES, idealState, resource);
    AssertJUnit.assertTrue(success);
    String path = PropertyPathConfig.getPath(PropertyType.IDEALSTATES, _clusterName, resource);
    AssertJUnit.assertTrue(_zkClient.exists(path));
    AssertJUnit.assertEquals(idealState.getRecord(), _zkClient.readData(path));

    idealState.setNumPartitions(20);
    success = _accessor.setProperty(PropertyType.IDEALSTATES, idealState, resource);
    AssertJUnit.assertTrue(success);
    AssertJUnit.assertTrue(_zkClient.exists(path));
    AssertJUnit.assertEquals(idealState.getRecord(), _zkClient.readData(path));
  }

  @Test ()
  public void testGet()
  {
    String path = PropertyPathConfig.getPath(PropertyType.IDEALSTATES, _clusterName, resource);
    IdealState idealState = new IdealState(resource);
    idealState.setIdealStateMode(IdealStateModeProperty.AUTO.toString());

    _zkClient.delete(path);
    _zkClient.createPersistent(new File(path).getParent(), true);
    _zkClient.createPersistent(path, idealState.getRecord());
    IdealState idealStateRead = _accessor.getProperty(IdealState.class, PropertyType.IDEALSTATES, resource);
    AssertJUnit.assertEquals(idealState.getRecord(), idealStateRead.getRecord());
  }

  @Test ()
  public void testRemove()
  {
    String path = PropertyPathConfig.getPath(PropertyType.IDEALSTATES, _clusterName, resource);
    IdealState idealState = new IdealState(resource);
    idealState.setIdealStateMode(IdealStateModeProperty.AUTO.toString());

    _zkClient.delete(path);
    _zkClient.createPersistent(new File(path).getParent(), true);
    _zkClient.createPersistent(path, idealState.getRecord());
    boolean success = _accessor.removeProperty(PropertyType.IDEALSTATES, resource);
    AssertJUnit.assertTrue(success);
    AssertJUnit.assertFalse(_zkClient.exists(path));
    IdealState idealStateRead = _accessor.getProperty(IdealState.class, PropertyType.IDEALSTATES, resource);
    AssertJUnit.assertNull(idealStateRead);

  }

  @Test ()
  public void testUpdate()
  {
    String path = PropertyPathConfig.getPath(PropertyType.IDEALSTATES, _clusterName, resource);
    IdealState idealState = new IdealState(resource);
    idealState.setIdealStateMode(IdealStateModeProperty.AUTO.toString());

    _zkClient.delete(path);
    _zkClient.createPersistent(new File(path).getParent(), true);
    _zkClient.createPersistent(path, idealState.getRecord());
    Stat stat = _zkClient.getStat(path);

    idealState.setIdealStateMode(IdealStateModeProperty.CUSTOMIZED.toString());

    boolean success = _accessor.updateProperty(PropertyType.IDEALSTATES, idealState, resource);
    AssertJUnit.assertTrue(success);
    AssertJUnit.assertTrue(_zkClient.exists(path));
    ZNRecord value = _zkClient.readData(path);
    AssertJUnit.assertEquals(idealState.getRecord(), value);
    Stat newstat = _zkClient.getStat(path);

    AssertJUnit.assertEquals(stat.getCtime(), newstat.getCtime());
    AssertJUnit.assertNotSame(stat.getMtime(), newstat.getMtime());
    AssertJUnit.assertTrue(stat.getMtime() < newstat.getMtime());
  }

  @Test ()
  public void testGetChildValues()
  {
    List<ExternalView> list = _accessor.getChildValues(ExternalView.class, PropertyType.EXTERNALVIEW, _clusterName);
    AssertJUnit.assertEquals(0, list.size());
  }

  @Test
  public void testBackToBackRemoveAndSet()
  {
    // CONFIG is cached
    _accessor.setProperty(PropertyType.CONFIGS, new ZNRecord("id1"), "config1");
    ZNRecord record = _accessor.getProperty(PropertyType.CONFIGS, "config1");
    // System.out.println(record.getId());
    Assert.assertEquals(record.getId(), "id1");
    String path = PropertyPathConfig.getPath(PropertyType.CONFIGS, _clusterName, "config1");
    _zkClient.delete(path);
    _zkClient.createPersistent(path, new ZNRecord("id1-new"));
    record = _accessor.getProperty(PropertyType.CONFIGS, "config1");
    // System.out.println(record.getId());
    Assert.assertEquals(record.getId(), "id1-new", "Should update cache since creation time is changed.");
  }

  @BeforeClass
  public void beforeClass() throws IOException, Exception
  {
    _clusterName = CLUSTER_PREFIX + "_" + getShortClassName();

		System.out.println("START TestZKDataAccessor at " + new Date(System.currentTimeMillis()));
		_zkClient = new ZkClient(ZK_ADDR);
		_zkClient.setZkSerializer(new ZNRecordSerializer());

    if (_zkClient.exists("/" + _clusterName))
    {
      _zkClient.deleteRecursive("/" + _clusterName);
    }
    _accessor = new ZKDataAccessor(_clusterName, _zkClient);
  }

  @AfterClass
  public void afterClass()
  {
		_zkClient.close();
		System.out.println("END TestZKDataAccessor at " + new Date(System.currentTimeMillis()));
  }
}
