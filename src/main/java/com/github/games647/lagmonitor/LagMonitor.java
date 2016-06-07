package com.github.games647.lagmonitor;

import com.github.games647.lagmonitor.commands.EnvironmentCommand;
import com.github.games647.lagmonitor.commands.FlightRecorderCommand;
import com.github.games647.lagmonitor.commands.GraphCommand;
import com.github.games647.lagmonitor.commands.HeapCommand;
import com.github.games647.lagmonitor.commands.MbeanCommand;
import com.github.games647.lagmonitor.commands.MonitorCommand;
import com.github.games647.lagmonitor.commands.NativeCommand;
import com.github.games647.lagmonitor.commands.PaginationCommand;
import com.github.games647.lagmonitor.commands.PaperTimingsCommand;
import com.github.games647.lagmonitor.commands.PingCommand;
import com.github.games647.lagmonitor.commands.StackTraceCommand;
import com.github.games647.lagmonitor.commands.SystemCommand;
import com.github.games647.lagmonitor.commands.TasksCommand;
import com.github.games647.lagmonitor.commands.ThreadCommand;
import com.github.games647.lagmonitor.commands.TimingCommand;
import com.github.games647.lagmonitor.commands.TpsHistoryCommand;
import com.github.games647.lagmonitor.commands.VmCommand;
import com.github.games647.lagmonitor.inject.CommandInjector;
import com.github.games647.lagmonitor.inject.ListenerInjector;
import com.github.games647.lagmonitor.inject.TaskInjector;
import com.github.games647.lagmonitor.listeners.PlayerPingListener;
import com.github.games647.lagmonitor.listeners.ThreadSafetyListener;
import com.github.games647.lagmonitor.storage.MonitorSaveTask;
import com.github.games647.lagmonitor.storage.NativeSaveTask;
import com.github.games647.lagmonitor.storage.Storage;
import com.github.games647.lagmonitor.storage.TpsSaveTask;
import com.github.games647.lagmonitor.tasks.BlockingIODetectorTask;
import com.github.games647.lagmonitor.tasks.PingHistoryTask;
import com.github.games647.lagmonitor.tasks.TpsHistoryTask;
import com.github.games647.lagmonitor.traffic.TrafficReader;
import com.google.common.collect.Maps;

import java.sql.SQLException;
import java.util.Map;
import java.util.Timer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.hyperic.sigar.Sigar;

public class LagMonitor extends JavaPlugin {

    //the server is pinging the client every 40 Ticks - so check it then
    //https://github.com/bergerkiller/CraftSource/blob/master/net.minecraft.server/PlayerConnection.java#L178
    private static final int PING_INTERVAL = 40;
    private static final int DETECTION_THRESHOLD = 10;

    private final Map<CommandSender, Pagination> paginations = Maps.newHashMap();

    private TpsHistoryTask tpsHistoryTask;
    private PingHistoryTask pingHistoryTask;
    private TrafficReader trafficReader;
    private Timer blockDetectionTimer;
    private Timer monitorTimer;
    private Storage storage;
    private Sigar sigar;

    public LagMonitor() {
        super();

        //setting the location where sigar can find the library
        //otherwise it would lookup the library path of Java
        System.setProperty("org.hyperic.sigar.path", getDataFolder().getPath());
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("default.jfc", false);
        registerCommands();

        if (getConfig().getBoolean("securityMangerBlockingCheck")) {
            SecurityManager oldSecurityManager = System.getSecurityManager();
            Thread mainThread = Thread.currentThread();
            System.setSecurityManager(new BlockingSecurityManager(this, mainThread, oldSecurityManager));
        }

        //register schedule tasks
        tpsHistoryTask = new TpsHistoryTask();
        pingHistoryTask = new PingHistoryTask();
        getServer().getScheduler().runTaskTimer(this, tpsHistoryTask, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, pingHistoryTask, 20L, PING_INTERVAL);

        if (getConfig().getBoolean("traffic-counter")) {
            trafficReader = new TrafficReader(this);
        }

        //register listeners
        getServer().getPluginManager().registerEvents(new PlayerPingListener(this), this);
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            //add the player to the list in the case the plugin is loaded at runtime
            pingHistoryTask.addPlayer(onlinePlayer);
        }

        if (getConfig().getBoolean("thread-safety-check")) {
            getServer().getPluginManager().registerEvents(new ThreadSafetyListener(this), this);
        }

        if (getConfig().getBoolean("thread-block-detection")) {
            blockDetectionTimer = new Timer(getName() + "-Thread-Blocking-Detection");
            BlockingIODetectorTask blockingIODetectorTask = new BlockingIODetectorTask(this, Thread.currentThread());
            blockDetectionTimer.scheduleAtFixedRate(blockingIODetectorTask, DETECTION_THRESHOLD, DETECTION_THRESHOLD);
        }

        if (getConfig().getBoolean("native-library")) {
            sigar = new Sigar();
        }

        if (getConfig().getBoolean("monitor-database")) {
            try {
                String host = getConfig().getString("host");
                int port = getConfig().getInt("port");
                String database = getConfig().getString("database");
                
                String username = getConfig().getString("username");
                String password = getConfig().getString("password");
                Storage localStorage = new Storage(this, host, port, database, username, password);
                localStorage.createTables();
                this.storage = localStorage;

                getServer().getScheduler().runTaskTimer(this, new TpsSaveTask(this), 20L
                        , getConfig().getInt("tps-save-interval") * 20L);
                //this can run async because it runs independently from the main thread
                getServer().getScheduler().runTaskTimerAsynchronously(this, new MonitorSaveTask(this), 20L
                        , getConfig().getInt("monitor-save-interval") * 20L);
                getServer().getScheduler().runTaskTimerAsynchronously(this, new NativeSaveTask(this), 20L
                        , getConfig().getInt("native-save-interval") * 20L);
            } catch (SQLException sqlEx) {
                getLogger().log(Level.SEVERE, "Failed to setup monitoring database", sqlEx);
            }
        }
    }

    @Override
    public void onDisable() {
        if (trafficReader != null) {
            trafficReader.close();
            trafficReader = null;
        }

        if (blockDetectionTimer != null) {
            blockDetectionTimer.cancel();
            blockDetectionTimer.purge();
            blockDetectionTimer = null;
        }

        if (monitorTimer != null) {
            monitorTimer.cancel();
            monitorTimer.purge();
            monitorTimer = null;
        }

        //restore the security manager
        SecurityManager securityManager = System.getSecurityManager();
        if (securityManager instanceof BlockingSecurityManager) {
            SecurityManager oldSecurityManager = ((BlockingSecurityManager) securityManager).getOldSecurityManager();
            System.setSecurityManager(oldSecurityManager);
        }

        if (sigar != null) {
            sigar.close();
        }

        for (Plugin plugin : getServer().getPluginManager().getPlugins()) {
            ListenerInjector.uninject(plugin);
            CommandInjector.uninject(plugin);
            TaskInjector.uninject(plugin);
        }
    }

    public Map<CommandSender, Pagination> getPaginations() {
        return paginations;
    }

    public Timer getMonitorTimer() {
        return monitorTimer;
    }

    public void setMonitorTimer(Timer monitorTimer) {
        this.monitorTimer = monitorTimer;
    }

    public TrafficReader getTrafficReader() {
        return trafficReader;
    }

    public TpsHistoryTask getTpsHistoryTask() {
        return tpsHistoryTask;
    }

    public PingHistoryTask getPingHistoryTask() {
        return pingHistoryTask;
    }

    public Sigar getSigar() {
        return sigar;
    }

    public Storage getStorage() {
        return storage;
    }

    private void registerCommands() {
        getCommand("ping").setExecutor(new PingCommand(this));
        getCommand("stacktrace").setExecutor(new StackTraceCommand(this));
        getCommand("thread").setExecutor(new ThreadCommand(this));
        getCommand("tpshistory").setExecutor(new TpsHistoryCommand(this));
        getCommand("mbean").setExecutor(new MbeanCommand(this));
        getCommand("system").setExecutor(new SystemCommand(this));
        getCommand("env").setExecutor(new EnvironmentCommand(this));
        getCommand("monitor").setExecutor(new MonitorCommand(this));
        getCommand("timing").setExecutor(new TimingCommand(this));
        getCommand("graph").setExecutor(new GraphCommand(this));
        getCommand("native").setExecutor(new NativeCommand(this));
        getCommand("vm").setExecutor(new VmCommand(this));
        getCommand("tasks").setExecutor(new TasksCommand(this));
        getCommand("paper").setExecutor(new PaperTimingsCommand(this));
        getCommand("heap").setExecutor(new HeapCommand(this));
        getCommand("lagpage").setExecutor(new PaginationCommand(this));
        getCommand("jfr").setExecutor(new FlightRecorderCommand(this));
    }
}
