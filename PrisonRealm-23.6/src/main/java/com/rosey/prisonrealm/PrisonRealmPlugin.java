package com.rosey.prisonrealm;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public final class PrisonRealmPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey itemKey;
    private final Map<String, Ritual> rituals = new HashMap<>();
    private final Map<UUID, UUID> sealedBy = new HashMap<>(); // prisoner -> original captor
    private final Map<UUID, GameMode> oldModes = new HashMap<>();
    private final Map<UUID, Location> releaseStations = new HashMap<>();
    private final Map<UUID, ReleaseSession> sessions = new HashMap<>();
    private Location prisonLocation;
    private int enforcementTask = -1;

    private static final class Ritual {
        final String key;
        final UUID owner, target;
        final Location core;
        final List<UUID> crystals = new ArrayList<>();
        int task = -1;
        int seconds = 30;
        Ritual(String key, UUID owner, UUID target, Location core) {
            this.key = key; this.owner = owner; this.target = target; this.core = core;
        }
    }

    private static final class ReleaseSession {
        final UUID owner, prisoner;
        final Location station;
        final Map<String, BlockData> changedBlocks = new LinkedHashMap<>();
        int task = -1;
        ItemStack previousHelmet;
        ReleaseSession(UUID owner, UUID prisoner, Location station) {
            this.owner = owner; this.prisoner = prisoner; this.station = station.clone();
        }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "prison_realm");
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("prisonrealm")).setExecutor(this);
        Objects.requireNonNull(getCommand("summon")).setExecutor(this);
        Objects.requireNonNull(getCommand("unseal")).setExecutor(this);
        registerRecipe();
        loadPrisonLocation();
        loadSealed();
        enforcementTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::enforceAllPrisoners, 20L, 20L);
        getLogger().info("Prison Realm 23.6 enabled for Paper 26.3.");
    }

    @Override public void onDisable() {
        if (enforcementTask != -1) Bukkit.getScheduler().cancelTask(enforcementTask);
        for (Ritual r : new ArrayList<>(rituals.values())) cancelRitual(r, null, true, true);
        for (ReleaseSession s : new ArrayList<>(sessions.values())) removeSession(s);
        saveSealed();
        savePrisonLocation();
    }

    private void registerRecipe() {
        NamespacedKey key = new NamespacedKey(this, "prison_realm_recipe");
        Bukkit.removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, createRealmItem());
        recipe.shape("RNR", "NEN", "RNR");
        recipe.setIngredient('R', Material.REDSTONE_BLOCK);
        recipe.setIngredient('N', Material.NETHER_STAR);
        recipe.setIngredient('E', Material.DRAGON_EGG);
        Bukkit.addRecipe(recipe);
    }

    private ItemStack createRealmItem() {
        ItemStack item = new ItemStack(Material.CRYING_OBSIDIAN);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("item.name", "&5&lPrison Realm")));
        meta.setLore(getConfig().getStringList("item.lore").stream().map(this::color).toList());
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        meta.setCustomModelData(getConfig().getInt("item.custom-model-data", 26001));
        if (getConfig().getBoolean("item.glow", true)) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }
        item.setItemMeta(meta);
        return item;
    }

    private boolean isRealm(ItemStack item) {
        return item != null && item.getType() == Material.CRYING_OBSIDIAN && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private String blockKey(Location l) { return l.getWorld().getName()+":"+l.getBlockX()+":"+l.getBlockY()+":"+l.getBlockZ(); }
    private void broadcast(String s) { Bukkit.broadcastMessage(color("&5[PrisonRealm] &d" + s)); }
    private boolean admin(CommandSender s) {
        if (!s.hasPermission("prisonrealm.admin")) { s.sendMessage("§cYou need prisonrealm.admin."); return false; }
        return true;
    }
    private void give(Player p) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(createRealmItem());
        left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
    }
    private Player online(String name) { return Bukkit.getPlayerExact(name); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("prisonrealm")) {
            if (args.length == 0) {
                sender.sendMessage("§5PrisonRealm 23.6: /prisonrealm give <player>, setprison, status, sealed, release <player>, reload");
                sender.sendMessage("§dPlace a Prison Realm near a target. After the 30-second escape window, capture is automatic.");
                sender.sendMessage("§dPlace another Realm at your release location, then /summon <player> and /unseal <player>.");
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "give" -> {
                    if (!admin(sender)) return true;
                    if (args.length < 2) { sender.sendMessage("§cUsage: /prisonrealm give <player>"); return true; }
                    Player p = online(args[1]); if (p == null) { sender.sendMessage("§cPlayer must be online."); return true; }
                    give(p); sender.sendMessage("§aGave Prison Realm to "+p.getName()+"."); return true;
                }
                case "setprison" -> {
                    if (!admin(sender)) return true;
                    if (!(sender instanceof Player p) || p.getWorld().getEnvironment() != World.Environment.NETHER) {
                        sender.sendMessage("§cStand at the prison spawn in the Nether and run /prisonrealm setprison."); return true;
                    }
                    prisonLocation = p.getLocation().clone(); savePrisonLocation();
                    sender.sendMessage("§aNether prison spawn saved."); return true;
                }
                case "status" -> {
                    sender.sendMessage("§5Active capture rituals: §f"+rituals.size()+" §7| Prisoners: §f"+sealedBy.size()+" §7| Summoning rituals: §f"+sessions.size());
                    return true;
                }
                case "sealed" -> {
                    if (sealedBy.isEmpty()) sender.sendMessage("§7No sealed players.");
                    else sealedBy.forEach((id, owner) -> sender.sendMessage("§d"+name(id)+" §7sealed by §f"+name(owner)));
                    return true;
                }
                case "release" -> {
                    if (!admin(sender)) return true;
                    if (args.length < 2) { sender.sendMessage("§cUsage: /prisonrealm release <player>"); return true; }
                    UUID id = findPlayerId(args[1]);
                    if (id == null || !sealedBy.containsKey(id)) { sender.sendMessage("§cThat player is not sealed."); return true; }
                    ReleaseSession session = sessions.get(id);
                    if (session != null) removeSession(session);
                    release(id, true, sender instanceof Player p ? p.getLocation() : prisonLocation);
                    sender.sendMessage("§aReleased "+args[1]+"."); return true;
                }
                case "reload" -> {
                    if (!admin(sender)) return true;
                    reloadConfig(); registerRecipe(); sender.sendMessage("§aConfig reloaded."); return true;
                }
                default -> { sender.sendMessage("§cUnknown subcommand. Use /prisonrealm."); return true; }
            }
        }
        if (cmd.equals("summon")) {
            if (!(sender instanceof Player owner)) { sender.sendMessage("§cPlayers only."); return true; }
            if (!owner.hasPermission("prisonrealm.use")) { owner.sendMessage("§cYou cannot use Prison Realm."); return true; }
            if (args.length < 1) { owner.sendMessage("§cUsage: /summon <player>"); return true; }
            Player prisoner = online(args[0]);
            if (prisoner == null || !sealedBy.containsKey(prisoner.getUniqueId())) { owner.sendMessage("§cThat player is not online or sealed."); return true; }
            if (!owner.getUniqueId().equals(sealedBy.get(prisoner.getUniqueId()))) { owner.sendMessage("§cOnly the original captor can summon this prisoner."); return true; }
            if (sessions.containsKey(prisoner.getUniqueId())) { owner.sendMessage("§cThis prisoner is already in a summoning ritual."); return true; }
            Location station = releaseStations.get(owner.getUniqueId());
            if (station == null || station.getWorld() == null || station.getBlock().getType() != Material.CRYING_OBSIDIAN) {
                owner.sendMessage("§cPlace a new Prison Realm block first."); return true;
            }
            if (!owner.getWorld().equals(station.getWorld()) || owner.getLocation().distanceSquared(station.clone().add(.5, .5, .5)) > 36) {
                owner.sendMessage("§cStand beside the Prison Realm you placed to summon the prisoner."); return true;
            }
            startSummon(owner, prisoner, station);
            return true;
        }
        if (cmd.equals("unseal")) {
            if (!(sender instanceof Player owner)) { sender.sendMessage("§cPlayers only."); return true; }
            if (args.length < 1) { owner.sendMessage("§cUsage: /unseal <player>"); return true; }
            Player prisoner = online(args[0]);
            if (prisoner == null || !sealedBy.containsKey(prisoner.getUniqueId())) { owner.sendMessage("§cThat player is not online or sealed."); return true; }
            if (!owner.getUniqueId().equals(sealedBy.get(prisoner.getUniqueId()))) { owner.sendMessage("§cOnly the original captor can unseal this player."); return true; }
            ReleaseSession session = sessions.get(prisoner.getUniqueId());
            if (session == null || !session.owner.equals(owner.getUniqueId())) { owner.sendMessage("§cRun /summon <player> first."); return true; }
            if (!owner.getWorld().equals(session.station.getWorld()) || owner.getLocation().distanceSquared(session.station.clone().add(.5,.5,.5)) > 36) {
                owner.sendMessage("§cYou must be at the Prison Realm where you summoned the player."); return true;
            }
            Location exit = session.station.clone().add(.5, 1, .5);
            removeSession(session);
            release(prisoner.getUniqueId(), true, exit);
            Block stationBlock = session.station.getBlock();
            if (stationBlock.getType() == Material.CRYING_OBSIDIAN) stationBlock.setType(Material.AIR);
            releaseStations.remove(owner.getUniqueId());
            give(owner);
            broadcast(prisoner.getName()+" has been unsealed by "+owner.getName()+".");
            return true;
        }
        return false;
    }

    @EventHandler public void onPlace(BlockPlaceEvent e) {
        if (!isRealm(e.getItemInHand())) return;
        Player owner = e.getPlayer();
        Location core = e.getBlockPlaced().getLocation();
        boolean ownsPrisoner = sealedBy.values().stream().anyMatch(id -> id.equals(owner.getUniqueId()));
        if (ownsPrisoner) {
            if (releaseStations.containsKey(owner.getUniqueId())) {
                e.setCancelled(true); owner.sendMessage("§cYou already have a release station placed."); return;
            }
            releaseStations.put(owner.getUniqueId(), core.clone());
            owner.sendMessage("§dRelease station placed. Run §f/summon <player> §dwhile standing beside it.");
            return;
        }
        if (!rituals.isEmpty() || !sealedBy.isEmpty()) {
            e.setCancelled(true); owner.sendMessage("§cPrison Realm is already occupied. Only one prisoner can exist at a time."); return;
        }
        Player target = Bukkit.getOnlinePlayers().stream()
                .filter(p -> !p.equals(owner) && !sealedBy.containsKey(p.getUniqueId()))
                .filter(p -> p.getWorld().equals(core.getWorld()) && insideArea(p.getLocation(), core))
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(core.clone().add(.5,.5,.5))))
                .orElse(null);
        if (target == null) {
            Bukkit.getScheduler().runTask(this, () -> returnPlacedRealm(owner, core));
            return;
        }
        Ritual r = new Ritual(blockKey(core), owner.getUniqueId(), target.getUniqueId(), core.clone());
        rituals.put(r.key, r);
        spawnCrystals(r);
        broadcast(owner.getName()+" activated a Prison Realm! "+target.getName()+" has 30 seconds to escape!");
        owner.sendMessage("§dCapture ritual started. If the target leaves the 30×30 area, the Realm returns to your inventory.");
        r.task = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> tickRitual(r), 0L, 20L);
    }

    private void returnPlacedRealm(Player owner, Location core) {
        if (core.getBlock().getType() == Material.CRYING_OBSIDIAN) core.getBlock().setType(Material.AIR);
        give(owner);
        owner.sendMessage("§cNo target was inside the capture area. Prison Realm returned to you.");
    }

    private void spawnCrystals(Ritual r) {
        World w = r.core.getWorld();
        int[][] offsets = {{4,0},{-4,0},{0,4},{0,-4}};
        for (int[] off : offsets) {
            Location at = r.core.clone().add(.5+off[0], 2.5, .5+off[1]);
            EnderCrystal crystal = w.spawn(at, EnderCrystal.class, ent -> {
                ent.setShowingBottom(false); ent.setInvulnerable(true);
                ent.setSilent(true); ent.setPersistent(false);
            });
            r.crystals.add(crystal.getUniqueId());
        }
    }

    private void tickRitual(Ritual r) {
        if (!rituals.containsKey(r.key)) return;
        Player owner = Bukkit.getPlayer(r.owner), target = Bukkit.getPlayer(r.target);
        if (owner == null || !owner.isOnline() || owner.isDead()) { cancelRitual(r, "Captor ritual cancelled.", true, true); return; }
        if (target == null || !target.isOnline()) { cancelRitual(r, "Target disconnected; ritual cancelled.", true, true); return; }
        if (r.core.getBlock().getType() != Material.CRYING_OBSIDIAN) { cancelRitual(r, "Prison Realm block was removed; ritual cancelled.", true, true); return; }
        if (!target.getWorld().equals(r.core.getWorld()) || !insideArea(target.getLocation(), r.core)) {
            cancelRitual(r, target.getName()+" escaped during sealing! Prison Realm returned to "+owner.getName()+".", true, true); return;
        }
        target.sendActionBar(color("&5Prison Realm capture in &d"+r.seconds+"s"));
        owner.sendActionBar(color("&5Sealing &d"+target.getName()+" &7| &f"+r.seconds+"s"));
        for (UUID id : r.crystals) {
            if (Bukkit.getEntity(id) instanceof EnderCrystal crystal)
                crystal.setBeamTarget(target.getLocation().clone().add(0, 1, 0));
        }
        if (r.seconds <= 0) {
            sealTarget(r, target, owner);
            return;
        }
        r.seconds--;
    }

    private boolean insideArea(Location p, Location core) {
        return p.getWorld().equals(core.getWorld())
                && Math.abs(p.getX() - (core.getBlockX()+.5)) <= 15
                && Math.abs(p.getZ() - (core.getBlockZ()+.5)) <= 15;
    }

    private void sealTarget(Ritual r, Player target, Player owner) {
        if (prisonLocation == null || prisonLocation.getWorld() == null
                || prisonLocation.getWorld().getEnvironment() != World.Environment.NETHER) {
            owner.sendMessage("§cAdmin has not configured the Nether prison. Ask an admin to run /prisonrealm setprison.");
            cancelRitual(r, "Capture failed: prison spawn is not configured.", true, true);
            return;
        }
        sealedBy.put(target.getUniqueId(), owner.getUniqueId());
        oldModes.put(target.getUniqueId(), target.getGameMode());
        clearSummonEffects(target);
        target.setInvulnerable(true);
        target.setGameMode(GameMode.ADVENTURE);
        target.setFireTicks(0);
        target.setFallDistance(0);
        target.teleport(prisonLocation.clone());
        applyPrisonEffects(target);
        cancelRitual(r, null, false, false);
        if (r.core.getBlock().getType() == Material.CRYING_OBSIDIAN) r.core.getBlock().setType(Material.AIR);
        saveSealed();
        target.sendTitle(color("&5SEALED"), color("&dWeakness • Silence • Immortality"), 10, 80, 20);
        target.sendMessage("§5You are sealed. You may move inside the prison, but cannot chat or escape.");
        owner.sendMessage("§aYou successfully sealed "+target.getName()+".");
        broadcast(owner.getName()+" sealed "+target.getName()+" inside the Prison Realm.");
    }

    private void cancelRitual(Ritual r, String message, boolean returnItem, boolean restoreTarget) {
        if (r.task != -1) Bukkit.getScheduler().cancelTask(r.task);
        rituals.remove(r.key);
        if (restoreTarget) {
            Player target = Bukkit.getPlayer(r.target);
            if (target != null) clearSummonEffects(target);
        }
        for (UUID id : r.crystals) {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
        if (returnItem) {
            Block block = r.core.getBlock();
            if (block.getType() == Material.CRYING_OBSIDIAN) block.setType(Material.AIR);
            Player owner = Bukkit.getPlayer(r.owner);
            if (owner != null) give(owner);
            else r.core.getWorld().dropItemNaturally(r.core.clone().add(.5,1,.5), createRealmItem());
        }
        if (message != null) broadcast(message);
    }

    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (sealedBy.containsKey(id)) {
            ReleaseSession session = sessions.get(id);
            if (session != null && (e.getTo().getWorld() != session.station.getWorld()
                    || e.getTo().distanceSquared(session.station) > 1.2)) {
                Location hold = session.station.clone().add(.5, 0, .5);
                hold.setYaw(e.getTo().getYaw()); hold.setPitch(e.getTo().getPitch());
                e.setTo(hold);
            } else if (session == null && outsidePrison(e.getTo())) {
                Location back = p.getLocation().clone();
                back.setYaw(e.getTo().getYaw()); back.setPitch(e.getTo().getPitch());
                e.setTo(back);
                p.sendActionBar(color("&cThe Prison Realm prevents escape."));
            }
            return;
        }
        Ritual r = rituals.values().stream().filter(x -> x.target.equals(id)).findFirst().orElse(null);
        if (r != null && !insideArea(e.getTo(), r.core))
            cancelRitual(r, p.getName()+" escaped during sealing! The Prison Realm returned to its owner.", true, true);
    }

    private boolean outsidePrison(Location loc) {
        if (prisonLocation == null || prisonLocation.getWorld() == null) return false;
        if (!loc.getWorld().equals(prisonLocation.getWorld())) return true;
        double radius = getConfig().getDouble("prison.escape-radius", 24.0);
        return Math.abs(loc.getX()-prisonLocation.getX()) > radius
                || Math.abs(loc.getZ()-prisonLocation.getZ()) > radius;
    }

    private void applyPrisonEffects(Player p) {
        p.setInvulnerable(true);
        p.setGameMode(GameMode.ADVENTURE);
        p.setFireTicks(0); p.setFallDistance(0);
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, Integer.MAX_VALUE, 2, false, false, true));
    }

    private void clearSummonEffects(Player p) {
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.removePotionEffect(PotionEffectType.WEAKNESS);
        p.setSneaking(false);
        p.setInvulnerable(true);
        p.setFireTicks(0);
    }

    private void enforceAllPrisoners() {
        for (UUID id : new ArrayList<>(sealedBy.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;
            applyPrisonEffects(p);
            ReleaseSession session = sessions.get(id);
            if (session != null) {
                p.setSneaking(true);
                if (p.getLocation().distanceSquared(session.station.clone().add(.5,0,.5)) > 1.2)
                    p.teleport(session.station.clone().add(.5,0,.5));
            } else if (outsidePrison(p.getLocation())) {
                p.teleport(prisonLocation.clone());
            }
        }
    }

    @EventHandler public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && sealedBy.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler public void onChat(AsyncChatEvent e) {
        if (sealedBy.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(color("&cYou cannot speak while sealed."));
        }
    }

    @EventHandler public void onCommand(PlayerCommandPreprocessEvent e) {
        if (sealedBy.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(color("&cCommands are disabled while sealed."));
        }
    }

    @EventHandler public void onPortal(PlayerPortalEvent e) {
        if (sealedBy.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler public void onBreak(BlockBreakEvent e) {
        if (sealedBy.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
        Ritual r = rituals.get(blockKey(e.getBlock().getLocation()));
        if (r != null) { e.setCancelled(true); e.getPlayer().sendMessage("§cYou cannot break an active Prison Realm."); }
    }

    @EventHandler public void onPlaceBlock(BlockPlaceEvent e) {
        if (sealedBy.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Ritual owned = rituals.values().stream().filter(r -> r.owner.equals(id)).findFirst().orElse(null);
        if (owned != null) cancelRitual(owned, "Captor disconnected; ritual cancelled.", true, true);
        Ritual target = rituals.values().stream().filter(r -> r.target.equals(id)).findFirst().orElse(null);
        if (target != null) cancelRitual(target, "Target disconnected; ritual cancelled.", true, true);
        saveSealed();
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        if (sealedBy.containsKey(e.getPlayer().getUniqueId())) {
            applyPrisonEffects(e.getPlayer());
            Bukkit.getScheduler().runTask(this, () -> {
                Player p = e.getPlayer();
                if (sessions.containsKey(p.getUniqueId())) p.teleport(sessions.get(p.getUniqueId()).station.clone().add(.5,0,.5));
                else if (prisonLocation != null) p.teleport(prisonLocation.clone());
            });
        }
    }

    @EventHandler public void onWorldChange(PlayerChangedWorldEvent e) {
        if (sealedBy.containsKey(e.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTask(this, () -> {
                Player p = e.getPlayer();
                if (sessions.containsKey(p.getUniqueId())) p.teleport(sessions.get(p.getUniqueId()).station.clone().add(.5,0,.5));
                else if (prisonLocation != null) p.teleport(prisonLocation.clone());
            });
        }
    }

    private void startSummon(Player owner, Player prisoner, Location station) {
        ReleaseSession s = new ReleaseSession(owner.getUniqueId(), prisoner.getUniqueId(), station);
        if (!buildPillars(s)) {
            owner.sendMessage("§cThe four pillar spaces must be clear. Clear the area and try again.");
            return;
        }
        sessions.put(prisoner.getUniqueId(), s);
        prisoner.teleport(station.clone().add(.5,0,.5));
        prisoner.setInvulnerable(true);
        s.previousHelmet = prisoner.getInventory().getHelmet() == null ? null : prisoner.getInventory().getHelmet().clone();
        ItemStack blindfold = new ItemStack(Material.LEATHER_HELMET);
        LeatherArmorMeta blindfoldMeta = (LeatherArmorMeta) blindfold.getItemMeta();
        blindfoldMeta.setColor(Color.BLACK);
        blindfoldMeta.setDisplayName(color("&8Prison Realm Blindfold"));
        blindfold.setItemMeta(blindfoldMeta);
        prisoner.getInventory().setHelmet(blindfold);
        prisoner.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false, true));
        prisoner.setSneaking(true);
        s.task = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            Player p = Bukkit.getPlayer(s.prisoner);
            if (p == null || !p.isOnline()) return;
            Location center = s.station.clone().add(.5,1,.5);
            p.getWorld().spawnParticle(Particle.END_ROD, center.clone().add(0,0.7,0), 6, .25,.4,.25, .01);
            for (int i=0;i<4;i++) {
                double a = i*Math.PI/2.0;
                Location pillar = s.station.clone().add(.5+Math.cos(a)*3.0, 2.5, .5+Math.sin(a)*3.0);
                p.getWorld().spawnParticle(Particle.END_ROD, pillar, 5, .15,.2,.15, .01);
                BlockData chainData = Material.CHAIN.createBlockData();
                for (double t=0.2;t<1.0;t+=.2) {
                    Location chain = center.clone().add((pillar.getX()-center.getX())*t, (pillar.getY()-center.getY())*t, (pillar.getZ()-center.getZ())*t);
                    p.getWorld().spawnParticle(Particle.BLOCK, chain, 2, .05,.05,.05, 0, chainData);
                }
            }
        }, 0L, 5L);
        owner.sendMessage("§dSummoning ritual started. Run /unseal "+prisoner.getName()+" to release them.");
        prisoner.sendTitle(color("&5SUMMONED"), color("&dBlindfolded • Chained • Kneeling"), 5, 60, 10);
        broadcast(owner.getName()+" summoned "+prisoner.getName()+" from the Prison Realm.");
    }

    private boolean buildPillars(ReleaseSession s) {
        int[][] offsets = {{3,0},{-3,0},{0,3},{0,-3}};
        for (int[] off : offsets) {
            int x=s.station.getBlockX()+off[0], y=s.station.getBlockY(), z=s.station.getBlockZ()+off[1];
            for (int dy=0;dy<3;dy++) {
                Block b=s.station.getWorld().getBlockAt(x,y+dy,z);
                if (!b.isEmpty() && !b.isPassable()) { restoreBlocks(s); return false; }
                s.changedBlocks.put(blockKey(b.getLocation()), b.getBlockData().clone());
                b.setType(dy==2 ? Material.SOUL_LANTERN : Material.POLISHED_BLACKSTONE_BRICKS, false);
            }
        }
        return true;
    }

    private void restoreBlocks(ReleaseSession s) {
        for (Map.Entry<String, BlockData> entry : s.changedBlocks.entrySet()) {
            String[] parts = entry.getKey().split(":");
            World w = Bukkit.getWorld(parts[0]);
            if (w == null) continue;
            Block b = w.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            b.setBlockData(entry.getValue(), false);
        }
        s.changedBlocks.clear();
    }

    private void removeSession(ReleaseSession s) {
        if (s.task != -1) Bukkit.getScheduler().cancelTask(s.task);
        restoreBlocks(s);
        sessions.remove(s.prisoner);
        Player p = Bukkit.getPlayer(s.prisoner);
        if (p != null) {
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            p.setSneaking(false);
            p.getInventory().setHelmet(s.previousHelmet);
        }
    }

    private void release(UUID id, boolean restoreMode, Location destination) {
        Player p = Bukkit.getPlayer(id);
        sealedBy.remove(id);
        if (p != null) {
            p.removePotionEffect(PotionEffectType.WEAKNESS);
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            p.setInvulnerable(false);
            p.setSneaking(false);
            if (restoreMode) p.setGameMode(oldModes.getOrDefault(id, GameMode.SURVIVAL));
            if (destination != null && destination.getWorld() != null) p.teleport(destination);
            p.sendTitle(color("&aUNSEALED"), color("&7You are free again."), 5, 50, 10);
        }
        oldModes.remove(id);
        sessions.remove(id);
        saveSealed();
    }

    private String name(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p == null ? id.toString().substring(0,8) : p.getName();
    }
    private UUID findPlayerId(String name) {
        Player p = online(name);
        if (p != null) return p.getUniqueId();
        try { return UUID.fromString(name); } catch (IllegalArgumentException ignored) { return null; }
    }

    private void savePrisonLocation() {
        if (prisonLocation == null || prisonLocation.getWorld() == null) return;
        getConfig().set("prison.world", prisonLocation.getWorld().getName());
        getConfig().set("prison.x", prisonLocation.getX()); getConfig().set("prison.y", prisonLocation.getY());
        getConfig().set("prison.z", prisonLocation.getZ()); getConfig().set("prison.yaw", prisonLocation.getYaw());
        getConfig().set("prison.pitch", prisonLocation.getPitch()); saveConfig();
    }
    private void loadPrisonLocation() {
        String world = getConfig().getString("prison.world");
        if (world == null) return;
        World w = Bukkit.getWorld(world); if (w == null) return;
        prisonLocation = new Location(w, getConfig().getDouble("prison.x"), getConfig().getDouble("prison.y"),
                getConfig().getDouble("prison.z"), (float)getConfig().getDouble("prison.yaw"), (float)getConfig().getDouble("prison.pitch"));
    }
    private void saveSealed() {
        List<String> rows = new ArrayList<>();
        for (Map.Entry<UUID, UUID> e : sealedBy.entrySet())
            rows.add(e.getKey()+"|"+e.getValue()+"|"+oldModes.getOrDefault(e.getKey(),GameMode.SURVIVAL).name());
        getConfig().set("sealed-players", rows);
        saveConfig();
    }
    private void loadSealed() {
        for (String row : getConfig().getStringList("sealed-players")) {
            String[] p = row.split("\\|");
            if (p.length < 2) continue;
            try {
                UUID prisoner=UUID.fromString(p[0]), owner=UUID.fromString(p[1]);
                sealedBy.put(prisoner,owner);
                if (p.length>=3) oldModes.put(prisoner,GameMode.valueOf(p[2]));
            } catch (IllegalArgumentException ex) { getLogger().warning("Skipping invalid prisoner record: "+row); }
        }
    }
}
