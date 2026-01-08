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
import org.bukkit.event.player.PlayerRespawnEvent;
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

    private List<String> deathMessages;
    private final Random random = new Random();

    // Потерю сердец жертве применяем ПОСЛЕ респавна (иначе Paper может багаться с возрождением)
    private final Map<UUID, Integer> pendingLoss = new HashMap<>();

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

        for (Player p : Bukkit.getOnlinePlayers()) {
            clampMaxHealth(p);
        }

        getLogger().info("HeartBounty включён.");
    }

    private void reloadSettings() {
        FileConfiguration cfg = getConfig();

        this.minHearts = Math.max(1, cfg.getInt("minHearts", 1));
        this.maxHearts = Math.max(this.minHearts, cfg.getInt("maxHearts", 20));
        this.heartsPerKill = Math.max(0, cfg.getInt("heartsPerKill", 1));

        String mat = cfg.getString("withdrawItemMaterial", "NETHER_STAR");
        Material m = Material.matchMaterial(mat == null ? "" : mat);
        this.withdrawMaterial = (m == null) ? Material.NETHER_STAR : m;

        this.withdrawName = cfg.getString("withdrawItemName", "&cСердце");
        this.withdrawLore = cfg.getStringList("withdrawItemLore");
        if (this.withdrawLore == null || this.withdrawLore.isEmpty()) {
            this.withdrawLore = List.of("&7ПКМ: получить &c+1 сердце&7.");
        }

        this.deathMessages = cfg.getStringList("death-messages");
        if (this.deathMessages == null || this.deathMessages.isEmpty()) {
            this.deathMessages = defaultDeathMessages();
        }
    }

    /* -----------------------------
       Сердца / здоровье
       ----------------------------- */

    private static double heartsToHealth(int hearts) {
        return hearts * 2.0;
    }

    private static int healthToHearts(double health) {
        return (int) Math.round(health / 2.0);
    }

    private int getMaxHearts(Player p) {
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return 10;
        return Math.max(1, healthToHearts(inst.getBaseValue()));
    }

    private void setMaxHearts(Player p, int hearts) {
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) return;

        int clamped = Math.min(maxHearts, Math.max(minHearts, hearts));
        double newMax = heartsToHealth(clamped);

        inst.setBaseValue(newMax);

        // КРИТИЧНО: НЕ трогать здоровье, пока игрок мёртв (может сломать возрождение)
        if (p.isDead()) return;

        // Поджимаем текущее здоровье под новый максимум
        if (p.getHealth() > newMax) p.setHealth(newMax);
    }

    private void addHearts(Player p, int delta) {
        setMaxHearts(p, getMaxHearts(p) + delta);
    }

    private void clampMaxHealth(Player p) {
        setMaxHearts(p, getMaxHearts(p));
    }

    /* -----------------------------
       PvP логика
       ----------------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        // Естественная смерть — сердца не меняем
        if (killer == null || killer.equals(victim)) return;
        if (heartsPerKill <= 0) return;

        // Убираем ванильное сообщение смерти (чтобы не дублировалось)
        event.setDeathMessage(null);

        // Молния — только эффект (без урона/огня/разрушений)
        victim.getWorld().strikeLightningEffect(victim.getLocation());

        // Случайное сообщение в чат
        String template = deathMessages.get(random.nextInt(deathMessages.size()));
        String msg = template
                .replace("{killer}", killer.getName())
                .replace("{victim}", victim.getName());
        Bukkit.broadcastMessage(color(msg));

        // Убийце +сердца сразу (безопасно)
        addHearts(killer, +heartsPerKill);
        killer.sendMessage(ChatColor.RED + "Ты получил(а) +" + heartsPerKill + " сердце(ц).");

        // Жертве -сердца ПОСЛЕ респавна (чтобы не ломать respawn)
        pendingLoss.merge(victim.getUniqueId(), heartsPerKill, Integer::sum);
        victim.sendMessage(ChatColor.DARK_RED + "После возрождения ты потеряешь -" + heartsPerKill + " сердце(ц).");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Integer loss = pendingLoss.remove(p.getUniqueId());
        if (loss == null || loss <= 0) return;

        Bukkit.getScheduler().runTask(this, () -> addHearts(p, -loss));
    }

    /* -----------------------------
       Предмет-сердце: вывод/использование
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

    // Использование сердца в руке (работает даже если другие плагины cancel interact)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUseHeartItem(PlayerInteractEvent event) {
        if (event.getItem() == null) return;
        Player p = event.getPlayer();

        Integer hearts = getHeartsInItem(event.getItem());
        if (hearts == null || hearts <= 0) return;

        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {}
            default -> { return; }
        }

        int current = getMaxHearts(p);
        if (current >= maxHearts) {
            p.sendMessage(ChatColor.GRAY + "У тебя уже максимум: " + maxHearts + " сердец.");
            event.setCancelled(true);
            return;
        }

        addHearts(p, hearts);

        ItemStack inHand = event.getItem();
        int amt = inHand.getAmount();
        if (amt <= 1) {
            p.getInventory().setItem(event.getHand(), new ItemStack(Material.AIR));
        } else {
            inHand.setAmount(amt - 1);
        }

        p.updateInventory();
        p.sendMessage(ChatColor.RED + "Ты использовал(а) +" + hearts + " сердце(ц).");
        event.setCancelled(true);
    }

    /* -----------------------------
       Команды (НЕ переводил названия команд)
       ----------------------------- */

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (command.getName().equalsIgnoreCase("withdraw")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Только для игроков.");
                return true;
            }
            if (!sender.hasPermission("heartbounty.withdraw")) {
                sender.sendMessage(ChatColor.RED + "Нет прав.");
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
                p.sendMessage(ChatColor.RED + "Нельзя вывести столько. Минимум: " + minHearts + " сердец.");
                return true;
            }

            setMaxHearts(p, remaining);

            for (int i = 0; i < amount; i++) {
                HashMap<Integer, ItemStack> leftover = p.getInventory().addItem(makeHeartItem(1));
                if (!leftover.isEmpty()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), makeHeartItem(1));
                }
            }

            p.sendMessage(ChatColor.RED + "Выведено " + amount + " сердце(ц) в предметы.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("hearts")) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.GRAY + "Использование: /hearts <add|set|giveitem|reload> ...");
                return true;
            }

            String sub = args[0].toLowerCase(Locale.ROOT);

            if (sub.equals("reload")) {
                if (!sender.hasPermission("heartbounty.admin")) {
                    sender.sendMessage(ChatColor.RED + "Нет прав.");
                    return true;
                }
                reloadConfig();
                reloadSettings();
                sender.sendMessage(ChatColor.GREEN + "HeartBounty перезагружен.");
                return true;
            }

            if (sub.equals("giveitem")) {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Только для игроков.");
                    return true;
                }
                int amount = 1;
                if (args.length >= 2) {
                    try { amount = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
                }
                amount = Math.max(1, amount);

                if (!sender.hasPermission("heartbounty.admin") && p.getGameMode() != GameMode.CREATIVE) {
                    sender.sendMessage(ChatColor.RED + "Нужно быть в креативе (или иметь админ-права).");
                    return true;
                }

                for (int i = 0; i < amount; i++) {
                    HashMap<Integer, ItemStack> leftover = p.getInventory().addItem(makeHeartItem(1));
                    if (!leftover.isEmpty()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), makeHeartItem(1));
                    }
                }
                p.sendMessage(ChatColor.RED + "Выдано " + amount + " предмет(ов) сердца.");
                return true;
            }

            if (sub.equals("add") || sub.equals("set")) {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.GRAY + "Использование: /hearts " + sub + " <player> <amount>");
                    return true;
                }

                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Игрок не найден (должен быть онлайн).");
                    return true;
                }

                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Количество должно быть числом.");
                    return true;
                }

                if (!sender.hasPermission("heartbounty.admin")) {
                    if (sender instanceof Player p && p.getGameMode() == GameMode.CREATIVE && p.getUniqueId().equals(target.getUniqueId())) {
                        // можно
                    } else {
                        sender.sendMessage(ChatColor.RED + "Нет прав.");
                        return true;
                    }
                }

                if (sub.equals("add")) {
                    addHearts(target, amount);
                    sender.sendMessage(ChatColor.GREEN + "Добавлено " + amount + " сердце(ц) игроку " + target.getName() + ".");
                } else {
                    setMaxHearts(target, amount);
                    int clamped = Math.min(maxHearts, Math.max(minHearts, amount));
                    sender.sendMessage(ChatColor.GREEN + "Макс.сердца игрока " + target.getName() + " установлены на " + clamped + ".");
                }
                return true;
            }

            sender.sendMessage(ChatColor.GRAY + "Использование: /hearts <add|set|giveitem|reload> ...");
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

    private static List<String> defaultDeathMessages() {
        return List.of(
                "&c[{killer}] &7отправляет &c[{victim}] &7в лобби!",
                "&c[{victim}] &7узнал, что PvP было включено",
                "&c[{killer}] &7показал &c[{victim}] &7где кнопка выхода",
                "&c[{victim}] &7не прошёл проверку на живучесть",
                "&c[{killer}] &7выдал &c[{victim}] &7бесплатный билет в спектаторы",
                "&c[{victim}] &7пошёл поспать. Навсегда.",
                "&c[{killer}] &7сказал &c[{victim}] &7«не сегодня»",
                "&c[{victim}] &7проиграл PvP и смысл жизни",
                "&c[{killer}] &7отключил &c[{victim}] &7от сервера (временно)",
                "&c[{victim}] &7узнал, что броня — это миф",
                "&c[{killer}] &7научил &c[{victim}] &7летать без элитр",
                "&c[{victim}] &7был удалён из реальности игроком &c[{killer}]",
                "&c[{killer}] &7сделал из &c[{victim}] &7декорацию",
                "&c[{victim}] &7забыл, как работает блокирование",
                "&c[{killer}] &7доказал &c[{victim}] &7что Ctrl — не спасает",
                "&c[{victim}] &7переоценил свои возможности",
                "&c[{killer}] &7отправил &c[{victim}] &7на перезагрузку",
                "&c[{victim}] &7получил урок PvP от &c[{killer}]",
                "&c[{killer}] &7объяснил &c[{victim}] &7что такое боль",
                "&c[{victim}] &7стал частью истории сервера",
                "&c[{victim}] &7нажал не те кнопки",
                "&c[{killer}] &7превратил &c[{victim}] &7в статистику",
                "&c[{victim}] &7думал, что это PvE",
                "&c[{killer}] &7выключил &c[{victim}] &7как лампочку",
                "&c[{victim}] &7попробовал… и не получилось",
                "&c[{killer}] &7написал &c[{victim}] &7прощальное сообщение",
                "&c[{victim}] &7не прошёл кастинг на выживание",
                "&c[{killer}] &7отправил &c[{victim}] &7в архив",
                "&c[{victim}] &7стал отрицательной статистикой",
                "&c[{killer}] &7сделал Ctrl+A → Ctrl+X из &c[{victim}]"
        );
    }
}
