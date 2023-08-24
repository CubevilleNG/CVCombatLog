package org.cubeville.cvcombatlog;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import net.quazar.offlinemanager.api.OfflineManagerAPI;
import net.quazar.offlinemanager.api.data.entity.IPlayerData;
import net.quazar.offlinemanager.api.inventory.AbstractPlayerInventory;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CVCombatLog extends JavaPlugin implements Listener {

    private Logger logger;
    private OfflineManagerAPI offlineManagerAPI;

    private Integer combatTimer;
    private Location spawnLocation;

    private Map<UUID, Integer> inCombat;
    private Map<UUID, BukkitTask> playerTasks;
    private Map<Integer, BossBar> bossbars;

    private NPCRegistry customRegistry;
    private Map<UUID, Integer> spawnedNPCs;

    public void onEnable() {
        this.logger = getLogger();
        this.offlineManagerAPI = (OfflineManagerAPI) Bukkit.getPluginManager().getPlugin("OfflineManager");
        this.inCombat = new HashMap<>();
        this.playerTasks = new HashMap<>();
        this.bossbars = new HashMap<>();
        this.spawnedNPCs = new HashMap<>();
        this.customRegistry = CitizensAPI.createNamedNPCRegistry("CVCombatLog", new MemoryNPCDataStore());

        File configFile = generateConfig();
        YamlConfiguration mainConfig = new YamlConfiguration();
        try {
            mainConfig.load(configFile);

            combatTimer = mainConfig.getInt("combat-timer");
            spawnLocation = mainConfig.getLocation("spawn-location");
        } catch(IOException | InvalidConfigurationException e) {
            logger.log(Level.WARNING, ChatColor.RED + "Unable to load config file", e);
            throw new RuntimeException(ChatColor.RED + "Unable to load config file", e);
        }

        for(int i = combatTimer; i >= 0; i--) {
            BossBar bossbar = Bukkit.createBossBar(ChatColor.DARK_AQUA + "Combat Timer: " + i + " seconds", BarColor.RED, BarStyle.SOLID);
            bossbar.setProgress(.1 * i);
            this.bossbars.put(i, bossbar);
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        logger.info(ChatColor.LIGHT_PURPLE + "Plugin Enabled Successfully");
    }

    public File generateConfig() {
        final File dataDir = getDataFolder();
        if(!dataDir.exists()) {
            dataDir.mkdirs();
        }
        File configFile = new File(dataDir, "config.yml");
        if(!configFile.exists()) {
            try {
                configFile.createNewFile();
                final InputStream inputStream = this.getResource(configFile.getName());
                final FileOutputStream fileOutputStream = new FileOutputStream(configFile);
                final byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = Objects.requireNonNull(inputStream).read(buffer)) != -1) {
                    fileOutputStream.write(buffer, 0, bytesRead);
                }
                fileOutputStream.flush();
                fileOutputStream.close();
            } catch(IOException e) {
                logger.log(Level.WARNING, ChatColor.RED + "Unable to generate config file", e);
                throw new RuntimeException(ChatColor.RED + "Unable to generate config file", e);
            }
        }
        return configFile;
    }

    public UUID getPlayerOfNPC(Integer npcID) {
        for(UUID pUUID : this.spawnedNPCs.keySet()) {
            if(this.spawnedNPCs.get(pUUID).equals(npcID)) {
                return pUUID;
            }
        }
        return null;
    }

    public void startCombatTimer(Player player) {
        this.playerTasks.put(player.getUniqueId(), Bukkit.getScheduler().runTaskTimer(this, () -> {
            boolean timerNull = this.inCombat.get(player.getUniqueId()) == null;
            int timer = this.inCombat.get(player.getUniqueId());
            hideBossbars(player);
            if(timerNull || timer - 1 < 0) {
                this.inCombat.remove(player.getUniqueId());
                exitCombat(player);
            } else {
                showBossbar(player, timer);
                this.inCombat.put(player.getUniqueId(), timer - 1);
            }
        }, 0, 20));
    }

    public void stopCombatTimer(UUID pUUID) {
        if(this.playerTasks.containsKey(pUUID)) {
            this.playerTasks.get(pUUID).cancel();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDamage(EntityDamageByEntityEvent e) {
        if(e.isCancelled()) return;
        Player source;
        if(e.getDamager() instanceof Player) {
            source = (Player) e.getDamager();
        } else if(e.getDamager() instanceof Arrow) {
            if(((Arrow)e.getDamager()).getShooter() instanceof Player) {
                source = (Player) ((Arrow) e.getDamager()).getShooter();
            } else {
                return;
            }
        } else {
            return;
        }
        if(e.getEntity() instanceof Player) {
            if(source.equals(e.getEntity())) return;
            if(this.customRegistry.getNPC(e.getEntity()) == null) {
                Player target = (Player) e.getEntity();
                exitCombat(source);
                exitCombat(target);
                enterCombat(source);
                enterCombat(target);
            } else {
                NPC target = this.customRegistry.getNPC(e.getEntity());
                UUID pUUID = getPlayerOfNPC(target.getId());
                if(pUUID == null) return;
                exitCombat(source);
                enterCombat(source);
                enterCombatNPC(pUUID);
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        if(this.customRegistry.getNPC(e.getEntity()) == null) {
            exitCombat(e.getEntity());
            hideBossbars(e.getEntity());
        } else {
            UUID pUUID = getPlayerOfNPC(this.customRegistry.getNPC(e.getEntity()).getId());
            if(pUUID == null) return;
            exitCombatNPC(pUUID);

            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(pUUID);
            IPlayerData playerData = offlineManagerAPI.getPlayerData(pUUID);
            AbstractPlayerInventory inv = playerData.getInventory();
            Integer exp = playerData.getExpLevel() * 7;
            for(ItemStack item : inv) {
                if(item != null) e.getEntity().getWorld().dropItem(e.getEntity().getLocation(), item); //drop players inventory for killer
            }
            e.getEntity().getWorld().spawn(e.getEntity().getLocation(), ExperienceOrb.class).setExperience(exp); //drop players xp for killer

            playerData.getInventory().clear(); //remove players inventory
            playerData.setExp(0F); //remove players xp
            playerData.setExpLevel(0); //remove players xp
            playerData.setFoodLevel(20); //reset players food
            playerData.setHealth(20F); //reset players health
            playerData.setLocation(offlinePlayer.getBedSpawnLocation() != null ? offlinePlayer.getBedSpawnLocation() : this.spawnLocation); //respawn player
            playerData.save();

        }
    }

    @EventHandler
    public void onPlayerLogout(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        if(this.inCombat.containsKey(player.getUniqueId())) {
            spawnNPC(player, player.getLocation());
            stopCombatTimer(e.getPlayer().getUniqueId());
            enterCombat(player);
        }
    }

    @EventHandler
    public void onPlayerLogin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        if(this.inCombat.containsKey(player.getUniqueId())) {
            exitCombat(player);
            startCombatTimer(player);
        }
    }

    public void enterCombat(Player player) {
        this.inCombat.put(player.getUniqueId(), this.combatTimer);
        startCombatTimer(player);
    }

    public void enterCombatNPC(UUID pUUID) {
        this.inCombat.put(pUUID, this.combatTimer);
    }

    public void exitCombat(Player player) {
        if(this.spawnedNPCs.containsKey(player.getUniqueId())) {
            despawnNPC(player.getUniqueId());
        }
        if(this.inCombat.get(player.getUniqueId()) == null || this.inCombat.get(player.getUniqueId()) <= 0) {
            this.inCombat.remove(player.getUniqueId());
        }
        stopCombatTimer(player.getUniqueId());
    }

    public void exitCombatNPC(UUID pUUID) {
        if(this.spawnedNPCs.containsKey(pUUID)) {
            despawnNPC(pUUID);
        }
        this.inCombat.remove(pUUID);
        stopCombatTimer(pUUID);
    }

    public void showBossbar(Player player, Integer time) {
        this.bossbars.get(time).addPlayer(player);
    }

    public void hideBossbars(Player player) {
        for(BossBar b : this.bossbars.values()) {
            b.removePlayer(player);
        }
    }

    public void spawnNPC(Player player, Location location) {
        String name = player.getName();
        String locString = Objects.requireNonNull(location.getWorld()).getName() + ";" + location.getBlockX() + ";" + location.getBlockY() + ";" + location.getBlockZ();
        NPC npc = this.customRegistry.createNPC(EntityType.PLAYER, name);
        SkinTrait skin = npc.getOrAddTrait(SkinTrait.class);
        skin.setSkinName(name);
        npc.addTrait(skin);
        npc.data().set(NPC.Metadata.DEFAULT_PROTECTED, false);
        npc.spawn(location);
        if(npc.isSpawned()) {
            this.spawnedNPCs.put(player.getUniqueId(), npc.getId());
            this.logger.log(Level.INFO, "NPC " + name + ":" + npc.getId() + " has been spawned at " + locString);
        } else {
            this.logger.log(Level.WARNING, "NPC " + name + ":" + npc.getId() + " FAILED to spawn at " + locString);
        }
    }

    public void despawnNPC(UUID pUUID) {
        if(this.spawnedNPCs.containsKey(pUUID) && this.customRegistry.getById(this.spawnedNPCs.get(pUUID)) != null) {
            NPC npc = this.customRegistry.getById(this.spawnedNPCs.get(pUUID));
            if(Bukkit.getPlayer(pUUID) != null) Bukkit.getPlayer(pUUID).teleport(npc.getEntity().getLocation());
            npc.destroy();
            this.spawnedNPCs.remove(pUUID);
            if(npc.isSpawned()) {
                this.logger.log(Level.WARNING, "NPC " + npc.getName() + ":" + npc.getId() + " FAILED to destroy");
            } else {
                this.logger.log(Level.INFO, "NPC " + npc.getName() + ":" + npc.getId() + " has been destroyed");
            }
        }
    }
}
