\
package com.example.heartbounty;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public final class HeartBountyPlugin extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey heartsKey;

    private int minHearts;
    private int maxHearts;
    private int heartsPerKill;

    private Material withdrawMaterial;
    private String withdrawName;
    private List<String> withdrawLore;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        this.heartsKey = new NamespacedKey(this, "hearts");

        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("withdraw")).setExecutor(this);
        Objects.requireNonNull(getCommand("withdraw")).setTabCompleter(this);

        Objects.requireNonNull(getCommand("hearts")).setExecutor(this);
        Objects.requireNonNull(getCommand("hearts")).setTabCompleter(this);

        // Clamp online players if /reload is used
        for (Player p : Bukkit.getOnlinePlayers()) {
            clampMaxHealth(p);
        }

        getLogger().info("HeartBounty enabled.");
    }

    private void reloadSettings() {
        FileConfiguration cfg = getConfig();
        this.minHearts = Math.max(1, cfg.getInt("minHearts", 1));
        this.maxHearts = Math.max(this.minHearts, cfg.getInt("maxHearts", 20));
        this.heartsPerKill = Math.max(0, cfg.getInt("heartsPerKill", 1));

        String mat = cfg.getString("withdrawItemMaterial", "NETHER_STAR");
        Material m = Material.matchMaterial(mat == null ? "" : mat);
        this.withdrawMaterial = (m == null) ? Material.NETHER_STAR : m;

        this.withdrawName = cfg.getString("withdrawItemName", "&cHeart");
        this.withdrawLore = cfg.getStringList("withdrawItemLore");
        if (this.withdrawLore == null) this.withdrawLore = List.of("&7Right-click to gain &c+1 heart&7.");
    }

    /* -----------------------------
       Core heart math
       ----------------------------- */

    private static double heartsToHealth(int hearts) {
        return hearts * 2.0;
    }

    private static int healthToHearts(double health) {
        // health points -> hearts (rounded to nearest whole heart, but we store as int hearts)
        return (int) Math.round(health / 2.0);
    }

    private int getMaxHearts(Player p) {
        AttributeInstance inst = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (inst == null) return 10; // vanilla fallback
        return Math.max(1, healthToHearts(inst.getBaseValue()));
    }

    private void setMaxHearts(Player p, int hearts) {
        AttributeInstance inst = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (inst == null) return;

        int clamped = Math.min(maxHearts, Math.max(minHearts, hearts));
        double newMax = heartsToHealth(clamped);

        inst.setBaseValue(newMax);

        // Keep current health within bounds
        if (p.getHealth() > newMax) p.setHealth(newMax);
        if (p.getHealth() <= 0) p.setHealth(Math.min(newMax, 1.0));
    }

    private void addHearts(Player p, int delta) {
        setMaxHearts(p, getMaxHearts(p) + delta);
    }

    private void clampMaxHealth(Player p) {
        setMaxHearts(p, getMaxHearts(p));
    }

    /* -----------------------------
       PvP logic
       ----------------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        // Natural causes (no player killer) => do nothing
        if (killer == null || killer.equals(victim)) return;

        if (heartsPerKill <= 0) return;

        // Victim loses hearts
        addHearts(victim, -heartsPerKill);

        // Killer gains hearts
        addHearts(killer, +heartsPerKill);

        // Optional: small feedback
        killer.sendMessage(ChatColor.RED + "You gained +" + heartsPerKill + " heart(s)!");
        victim.sendMessage(ChatColor.DARK_RED + "You lost -" + heartsPerKill + " heart(s)!");
    }

    /* -----------------------------
       Withdraw item + consume logic
       ----------------------------- */

    private ItemStack makeHeartItem(int amountHearts) {
        amountHearts = Math.max(1, amountHearts);

        ItemStack item = new ItemStack(withdrawMaterial, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(withdrawName));

            List<String> lore = withdrawLore.stream().map(HeartBountyPlugin::color).collect(Collectors.toList());
            meta.setLore(lore);

            meta.getPersistentDataContainer().set(heartsKey, PersistentDataType.INTEGER, amountHearts);
            item.setItemMeta(meta);
        }
        return item;
    }

    private Integer getHeartsInItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.get(heartsKey, PersistentDataType.INTEGER);
    }

    @EventHandler(ignoreCancelled = true)
    public void onUseHeartItem(PlayerInteractEvent event) {
        if (event.getItem() == null) return;
        Player p = event.getPlayer();

        Integer hearts = getHeartsInItem(event.getItem());
        if (hearts == null || hearts <= 0) return;

        // Only right click
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {}
            default -> { return; }
        }

        int current = getMaxHearts(p);
        if (current >= maxHearts) {
            p.sendMessage(ChatColor.GRAY + "You are already at the max of " + maxHearts + " hearts.");
            return;
        }

        // Apply
        addHearts(p, hearts);

        // Consume 1 item
        ItemStack inHand = event.getItem();
        int amt = inHand.getAmount();
        if (amt <= 1) {
            p.getInventory().setItem(event.getHand(), null);
        } else {
            inHand.setAmount(amt - 1);
        }

        p.sendMessage(ChatColor.RED + "You consumed +" + hearts + " heart(s).");
        event.setCancelled(true);
    }

    /* -----------------------------
       Commands
       ----------------------------- */

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (command.getName().equalsIgnoreCase("withdraw")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Players only.");
                return true;
            }
            if (!sender.hasPermission("heartbounty.withdraw")) {
                sender.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }

            int amount = 1;
            if (args.length >= 1) {
                try { amount = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
            }
            amount = Math.max(1, amount);

            int current = getMaxHearts(p);
            int remaining = current - amount;

            if (remaining < minHearts) {
                p.sendMessage(ChatColor.RED + "You can't withdraw that many. Minimum is " + minHearts + " hearts.");
                return true;
            }

            setMaxHearts(p, remaining);

            // Give items (1 per withdraw, each worth 1 heart)
            for (int i = 0; i < amount; i++) {
                HashMap<Integer, ItemStack> leftover = p.getInventory().addItem(makeHeartItem(1));
                if (!leftover.isEmpty()) {
                    // Drop if full inventory
                    p.getWorld().dropItemNaturally(p.getLocation(), makeHeartItem(1));
                }
            }

            p.sendMessage(ChatColor.RED + "Withdrew " + amount + " heart(s) into item(s).");
            return true;
        }

        if (command.getName().equalsIgnoreCase("hearts")) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.GRAY + "Usage: /hearts <add|set|giveitem|reload> ...");
                return true;
            }

            String sub = args[0].toLowerCase(Locale.ROOT);

            if (sub.equals("reload")) {
                if (!sender.hasPermission("heartbounty.admin")) {
                    sender.sendMessage(ChatColor.RED + "No permission.");
                    return true;
                }
                reloadConfig();
                reloadSettings();
                sender.sendMessage(ChatColor.GREEN + "HeartBounty reloaded.");
                return true;
            }

            if (sub.equals("giveitem")) {
                // /hearts giveitem <amount>
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                int amount = 1;
                if (args.length >= 2) {
                    try { amount = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
                }
                amount = Math.max(1, amount);

                // Allow: OP/admin OR creative mode (self)
                if (!sender.hasPermission("heartbounty.admin") && p.getGameMode() != GameMode.CREATIVE) {
                    sender.sendMessage(ChatColor.RED + "You must be in creative (or have admin permission) to do that.");
                    return true;
                }

                for (int i = 0; i < amount; i++) {
                    HashMap<Integer, ItemStack> leftover = p.getInventory().addItem(makeHeartItem(1));
                    if (!leftover.isEmpty()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), makeHeartItem(1));
                    }
                }
                p.sendMessage(ChatColor.RED + "Given " + amount + " heart item(s).");
                return true;
            }

            if (sub.equals("add") || sub.equals("set")) {
                // /hearts add <player> <amount>
                // /hearts set <player> <amount>
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.GRAY + "Usage: /hearts " + sub + " <player> <amount>");
                    return true;
                }

                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found (must be online).");
                    return true;
                }

                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Amount must be a number.");
                    return true;
                }

                // Allow:
                // - Admin permission always
                // - OR (creative player setting/adding to self)
                if (!sender.hasPermission("heartbounty.admin")) {
                    if (sender instanceof Player p && p.getGameMode() == GameMode.CREATIVE && p.getUniqueId().equals(target.getUniqueId())) {
                        // ok
                    } else {
                        sender.sendMessage(ChatColor.RED + "No permission.");
                        return true;
                    }
                }

                if (sub.equals("add")) {
                    addHearts(target, amount);
                    sender.sendMessage(ChatColor.GREEN + "Added " + amount + " heart(s) to " + target.getName() + ".");
                } else {
                    setMaxHearts(target, amount);
                    sender.sendMessage(ChatColor.GREEN + "Set " + target.getName() + " to " + Math.min(maxHearts, Math.max(minHearts, amount)) + " hearts.");
                }
                return true;
            }

            sender.sendMessage(ChatColor.GRAY + "Usage: /hearts <add|set|giveitem|reload> ...");
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

        if (command.getName().equalsIgnoreCase("withdraw")) {
            if (args.length == 1) return List.of("1", "2", "5", "10");
            return List.of();
        }

        if (command.getName().equalsIgnoreCase("hearts")) {
            if (args.length == 1) return List.of("add", "set", "giveitem", "reload");
            if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("set"))) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().collect(Collectors.toList());
            }
            if (args.length == 3 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("set"))) {
                return List.of("1", "5", "10", "20");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("giveitem")) {
                return List.of("1", "2", "5", "10");
            }
            return List.of();
        }

        return List.of();
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
