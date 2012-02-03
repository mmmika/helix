package com.linkedin.helix.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.Logger;

import com.linkedin.helix.ClusterView;
import com.linkedin.helix.PropertyType;
import com.linkedin.helix.ZNRecord;
import com.linkedin.helix.agent.zk.ZNRecordSerializer;
import com.linkedin.helix.agent.zk.ZkClient;
import com.linkedin.helix.model.CurrentState.CurrentStateProperty;
import com.linkedin.helix.model.LiveInstance.LiveInstanceProperty;
import com.linkedin.helix.participant.statemachine.StateModel;
import com.linkedin.helix.participant.statemachine.StateModelFactory;
import com.linkedin.helix.util.CMUtil;

public class ClusterStateVerifier
{
  public static String cluster = "cluster";
  public static String zkServerAddress = "zkSvr";
  public static String help = "help";
  private static Logger _logger = Logger.getLogger(ClusterStateVerifier.class);

  /**
   * @param args
   * @return
   */
  public static boolean verifyClusterStates(String zkServer, String clusterName)
  {
    ZkClient zkClient = new ZkClient(zkServer);
    zkClient.setZkSerializer(new ZNRecordSerializer());
    String instancesPath = CMUtil.getMemberInstancesPath(clusterName);

    // Make a copy of the current states
    List<String> instanceNames = zkClient.getChildren(instancesPath);

    Map<String, Map<String, ZNRecord>> currentStates = new TreeMap<String, Map<String, ZNRecord>>();

    for (String instanceName : instanceNames)
    {
      String liveInstancePath = CMUtil.getLiveInstancePath(clusterName, instanceName);
      ZNRecord liveInstanceRecord = zkClient.readData(liveInstancePath);
      if (!currentStates.containsKey(instanceName))
      {
        currentStates.put(instanceName, new TreeMap<String, ZNRecord>());
      }
      String currentStatePath = CMUtil.getCurrentStateBasePath(clusterName,
          instanceName)+"/"+ liveInstanceRecord.getSimpleField(LiveInstanceProperty.SESSION_ID.toString());
      List<String> partitionStatePaths = zkClient.getChildren(currentStatePath);
      for (String stateUnitKey : partitionStatePaths)
      {
        String partitionStatePath = currentStatePath + "/" + stateUnitKey;
        // System.out.println(partitionStatePath);
        ZNRecord nodeCurrentState = zkClient.readData(partitionStatePath);
        currentStates.get(instanceName).put(stateUnitKey, nodeCurrentState);
      }
    }

    // Make a copy of the ideal state
    String idealStatePath = CMUtil.getIdealStatePath(clusterName);
    List<String> stateGroups = zkClient.getChildren(idealStatePath);
    List<ZNRecord> idealStates = new ArrayList<ZNRecord>();

    for (String stateGroup : stateGroups)
    {
      String stateGroupPath = idealStatePath + "/" + stateGroup;
      idealStates.add((ZNRecord) zkClient.readData(stateGroupPath));
    }
    // Make a copy of external view
    String externalViewPath = CMUtil.getExternalViewPath(clusterName);
    List<String> viewGroups = zkClient.getChildren(externalViewPath);
    List<ZNRecord> externalViews = new ArrayList<ZNRecord>();

    for (String viewGroup : viewGroups)
    {
      String viewPath = externalViewPath + "/" + viewGroup;
      externalViews.add((ZNRecord) zkClient.readData(viewPath));
    }

    boolean result1 = verifyIdealStateAndCurrentState(idealStates,
        currentStates);
    boolean result2 = verifyCurrentStateAndExternalView(currentStates,
        externalViews);

    return result1 && result2;
  }

  public static boolean verifyFileBasedClusterStates(String file,
      String instanceName, StateModelFactory<StateModel> stateModelFactory)
  {
    // ClusterView clusterView = FileBasedClusterManager.readClusterView(file);
    ClusterView clusterView = ClusterViewSerializer.deserialize(new File(file));
    boolean ret = true;
    int nonOfflineStateNr = 0;

    // ideal_state for instance with name $instanceName
    Map<String, String> instanceIdealStates = new HashMap<String, String>();
    for (ZNRecord idealStateItem : clusterView
        .getPropertyList(PropertyType.IDEALSTATES))
    {
      Map<String, Map<String, String>> idealStates = idealStateItem
          .getMapFields();

      for (Map.Entry<String, Map<String, String>> entry : idealStates
          .entrySet())
      {
        if (entry.getValue().containsKey(instanceName))
        {
          String state = entry.getValue().get(instanceName);
          instanceIdealStates.put(entry.getKey(), state);
        }
      }
    }

    Map<String, StateModel> currentStateMap = stateModelFactory.getStateModelMap();

    if (currentStateMap.size() != instanceIdealStates.size())
    {
      _logger.warn("Number of current states (" + currentStateMap.size()
          + ") mismatch " + "number of ideal states ("
          + instanceIdealStates.size() + ")");
      return false;
    }

    for (Map.Entry<String, String> entry : instanceIdealStates.entrySet())
    {

      String stateUnitKey = entry.getKey();
      String idealState = entry.getValue();

      if (!idealState.equalsIgnoreCase("offline"))
      {
        nonOfflineStateNr++;
      }

      if (!currentStateMap.containsKey(stateUnitKey))
      {
        _logger.warn("Current state does not contain " + stateUnitKey);
        // return false;
        ret = false;
        continue;
      }

      String curState = currentStateMap.get(stateUnitKey).getCurrentState();
      if (!idealState.equalsIgnoreCase(curState))
      {
        _logger.info("State mismatch--unit_key:" + stateUnitKey + " cur:"
            + curState + " ideal:" + idealState + " instance_name:"
            + instanceName);
        // return false;
        ret = false;
        continue;
      }
    }

    if (ret == true)
    {
      System.out.println(instanceName + ": verification succeed");
      _logger.info(instanceName + ": verification succeed ("
          + nonOfflineStateNr + " states)");
    }

    return ret;
  }

  public static boolean verifyIdealStateAndCurrentState(
      List<ZNRecord> idealStates,
      Map<String, Map<String, ZNRecord>> currentStates)
  {
    int countInIdealStates = 0;
    int countInCurrentStates = 0;

    for (ZNRecord idealState : idealStates)
    {
      String stateUnitGroup = idealState.getId();

      Map<String, Map<String, String>> statesMap = idealState.getMapFields();
      for (String stateUnitKey : statesMap.keySet())
      {
        Map<String, String> partitionNodeStates = statesMap.get(stateUnitKey);
        for (String nodeName : partitionNodeStates.keySet())
        {
          countInIdealStates++;
          String nodePartitionState = partitionNodeStates.get(nodeName);
          if (!currentStates.containsKey(nodeName))
          {
            _logger.warn("Current state does not contain " + nodeName);
            return false;
          }
          if (!currentStates.get(nodeName).containsKey(stateUnitGroup))
          {
            _logger.warn("Current state for " + nodeName + " does not contain "
                + stateUnitGroup);
            return false;
          }
          if (!currentStates.get(nodeName).get(stateUnitGroup).getMapFields()
              .containsKey(stateUnitKey))
          {
            _logger.warn("Current state for" + nodeName + "with "+stateUnitGroup+" does not contain "
                + stateUnitKey);
            return false;
          }

          String partitionNodeState = currentStates.get(nodeName)
              .get(stateUnitGroup).getMapFields().get(stateUnitKey)
              .get(CurrentStateProperty.CURRENT_STATE.toString());
          boolean success =true;
          if (!partitionNodeState.equals(nodePartitionState))
          {
            _logger.warn("State mismatch " + stateUnitGroup + " " + stateUnitKey + " " +nodeName
                + " current:" + partitionNodeState + ", expected:" + nodePartitionState);
            success= false;
          }
          return success;
        }
      }
    }

    for (String nodeName : currentStates.keySet())
    {
      Map<String, ZNRecord> nodeCurrentStates = currentStates.get(nodeName);
      for (String stateUnitGroup : nodeCurrentStates.keySet())
      {
        for(String stateUnitKey : nodeCurrentStates.get(stateUnitGroup).getMapFields().keySet())
        {
          countInCurrentStates++;
        }
      }
    }
    // assert (countInIdealStates == countInCurrentStates);

    if (countInIdealStates != countInCurrentStates)
    {
      _logger.info("countInIdealStates:" + countInIdealStates
          + "countInCurrentStates: " + countInCurrentStates);
    }
    return countInIdealStates == countInCurrentStates;
  }

  public static boolean verifyCurrentStateAndExternalView(
      Map<String, Map<String, ZNRecord>> currentStates,
      List<ZNRecord> externalViews)
  {
    // currently external view and ideal state has same structure so we can
    // use the same verification code.
    return verifyIdealStateAndCurrentState(externalViews, currentStates);
  }

  @SuppressWarnings("static-access")
  private static Options constructCommandLineOptions()
  {
    Option helpOption = OptionBuilder.withLongOpt(help)
        .withDescription("Prints command-line options info").create();

    Option zkServerOption = OptionBuilder.withLongOpt(zkServerAddress)
        .withDescription("Provide zookeeper address").create();
    zkServerOption.setArgs(1);
    zkServerOption.setRequired(true);
    zkServerOption.setArgName("ZookeeperServerAddress(Required)");

    Option clusterOption = OptionBuilder.withLongOpt(cluster)
        .withDescription("Provide cluster name").create();
    clusterOption.setArgs(1);
    clusterOption.setRequired(true);
    clusterOption.setArgName("Cluster name (Required)");

    Options options = new Options();
    options.addOption(helpOption);
    options.addOption(zkServerOption);
    options.addOption(clusterOption);
    return options;
  }

  public static void printUsage(Options cliOptions)
  {
    HelpFormatter helpFormatter = new HelpFormatter();
    helpFormatter.printHelp("java " + ClusterSetup.class.getName(), cliOptions);
  }

  public static CommandLine processCommandLineArgs(String[] cliArgs)
  {
    CommandLineParser cliParser = new GnuParser();
    Options cliOptions = constructCommandLineOptions();
    CommandLine cmd = null;

    try
    {
      return cliParser.parse(cliOptions, cliArgs);
    } catch (ParseException pe)
    {
      System.err
          .println("CommandLineClient: failed to parse command-line options: "
              + pe.toString());
      printUsage(cliOptions);
      System.exit(1);
    }
    return null;
  }

  public static boolean verifyState(String[] args)
  {
    // TODO Auto-generated method stub
    String clusterName = "storage-cluster";
    String zkServer = "localhost:2181";
    if (args.length > 0)
    {
      CommandLine cmd = processCommandLineArgs(args);
      zkServer = cmd.getOptionValue(zkServerAddress);
      clusterName = cmd.getOptionValue(cluster);
    }
    return verifyClusterStates(zkServer, clusterName);

  }

  public static void main(String[] args)
  {
    boolean result = verifyState(args);
    System.out.println(result ? "Successful" : "failed");
  }

}