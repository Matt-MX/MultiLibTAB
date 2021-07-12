package me.neznamy.tab.shared.features.bossbar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.stream.Collectors;

import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.bossbar.BarColor;
import me.neznamy.tab.api.bossbar.BarStyle;
import me.neznamy.tab.api.bossbar.BossBar;
import me.neznamy.tab.api.bossbar.BossBarManager;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.cpu.UsageType;
import me.neznamy.tab.shared.features.PlaceholderManagerImpl;
import me.neznamy.tab.shared.features.TabFeature;
import me.neznamy.tab.shared.placeholders.ServerPlaceholder;

/**
 * Class for handling bossbar feature
 */
public class BossBarManagerImpl extends TabFeature implements BossBarManager {

	//default bossbars
	private List<String> defaultBars;

	//per-world / per-server bossbars
	private Map<String, List<String>> perWorld;

	//registered bossbars
	private Map<String, BossBar> lines = new HashMap<>();

	//toggle command
	private String toggleCommand;

	//list of currently running bossbar announcements
	private Set<BossBar> announcements = new HashSet<>();

	//saving toggle choice into file
	private boolean rememberToggleChoice;

	//players with toggled bossbar
	private List<String> bossbarOffPlayers = new ArrayList<>();

	//list of worlds / servers where bossbar feature is disabled entirely
	private List<String> disabledWorlds;

	//time when bossbar announce ends, used for placeholder
	private long announceEndTime;

	//if bossbar is hidden by default until toggle command is used
	private boolean hiddenByDefault;

	private Set<TabPlayer> playersInDisabledWorlds = new HashSet<>();

	private Set<TabPlayer> visiblePlayers = new HashSet<>();

	/**
	 * Constructs new instance and loads configuration
	 * @param tab - tab instance
	 */
	public BossBarManagerImpl() {
		disabledWorlds = TAB.getInstance().getConfiguration().getConfig().getStringList("bossbar.disable-in-"+TAB.getInstance().getPlatform().getSeparatorType()+"s", Arrays.asList("disabled" + TAB.getInstance().getPlatform().getSeparatorType()));
		toggleCommand = TAB.getInstance().getConfiguration().getConfig().getString("bossbar.toggle-command", "/bossbar");
		defaultBars = TAB.getInstance().getConfiguration().getConfig().getStringList("bossbar.default-bars", new ArrayList<>());
		hiddenByDefault = TAB.getInstance().getConfiguration().getConfig().getBoolean("bossbar.hidden-by-default", false);
		perWorld = TAB.getInstance().getConfiguration().getConfig().getConfigurationSection("bossbar.per-"+TAB.getInstance().getPlatform().getSeparatorType());
		for (Object bar : TAB.getInstance().getConfiguration().getConfig().getConfigurationSection("bossbar.bars").keySet()){
			lines.put(bar.toString(), loadFromConfig(bar.toString()));
		}
		for (String bar : new ArrayList<>(defaultBars)) {
			if (lines.get(bar) == null) {
				TAB.getInstance().getErrorManager().startupWarn("BossBar \"&e" + bar + "&c\" is defined as default bar, but does not exist! &bIgnoring.");
				defaultBars.remove(bar);
			}
		}
		for (Entry<String, List<String>> entry : perWorld.entrySet()) {
			List<String> bars = entry.getValue();
			for (String bar : new ArrayList<>(bars)) {
				if (lines.get(bar) == null) {
					TAB.getInstance().getErrorManager().startupWarn("BossBar \"&e" + bar + "&c\" is defined as per-world bar in world &e" + entry.getKey() + "&c, but does not exist! &bIgnoring.");
					bars.remove(bar);
				}
			}
		}
		rememberToggleChoice = TAB.getInstance().getConfiguration().getConfig().getBoolean("bossbar.remember-toggle-choice", false);
		if (rememberToggleChoice) {
			bossbarOffPlayers = TAB.getInstance().getConfiguration().getPlayerData("bossbar-off");
		}
		((PlaceholderManagerImpl) TAB.getInstance().getPlaceholderManager()).getAllUsedPlaceholderIdentifiers().add("%countdown%");
		TAB.getInstance().getPlaceholderManager().registerServerPlaceholder(new ServerPlaceholder("%countdown%", 100) {

			@Override
			public String get() {
				return String.valueOf((announceEndTime - System.currentTimeMillis()) / 1000);
			}
		});
		TAB.getInstance().debug(String.format("Loaded Bossbar feature with parameters disabledWorlds=%s, toggleCommand=%s, defaultBars=%s, hiddenByDefault=%s, perWorld=%s, remember_toggle_choice=%s",
				disabledWorlds, toggleCommand, defaultBars, hiddenByDefault, perWorld, rememberToggleChoice));
	}
	
	/**
	 * Loads bossbar from config by it's name
	 * @param bar - name of bossbar in config
	 * @return loaded bossbar
	 */
	private BossBarLine loadFromConfig(String bar) {
		String condition = TAB.getInstance().getConfiguration().getConfig().getString("bossbar.bars." + bar + ".display-condition", null);
		if (condition == null) {
			Object permRequired = TAB.getInstance().getConfiguration().getConfig().getBoolean("bossbar.bars." + bar + ".permission-required");
			if (permRequired != null && (boolean) permRequired) {
				condition = "permission:TAB.getInstance().bossbar." + bar;
			}
		}
		
		String style = TAB.getInstance().getConfiguration().getConfig().getString("bossbar.bars." + bar + ".style");
		String color = TAB.getInstance().getConfiguration().getConfig().getString("bossbar.bars." + bar + ".color");
		String progress = TAB.getInstance().getConfiguration().getConfig().getString("bossbar.bars." + bar + ".progress");
		String text = TAB.getInstance().getConfiguration().getConfig().getString("bossbar.bars." + bar + ".text");
		if (style == null) {
			TAB.getInstance().getErrorManager().missingAttribute("BossBar", bar, "style");
			style = "PROGRESS";
		}
		if (color == null) {
			TAB.getInstance().getErrorManager().missingAttribute("BossBar", bar, "color");
			color = "WHITE";
		}
		if (progress == null) {
			progress = "100";
			TAB.getInstance().getErrorManager().missingAttribute("BossBar", bar, "progress");
		}
		if (text == null) {
			text = "";
			TAB.getInstance().getErrorManager().missingAttribute("BossBar", bar, "text");
		}
		return new BossBarLine(bar, condition, color, style, text, progress);
	}

	@Override
	public void load() {
		for (TabPlayer p : TAB.getInstance().getPlayers()) {
			onJoin(p);
		}
		TAB.getInstance().getCPUManager().startRepeatingMeasuredTask(1000, "refreshing bossbar permissions", getFeatureType(), UsageType.REPEATING_TASK, () -> {

			for (TabPlayer p : TAB.getInstance().getPlayers()) {
				if (!p.isLoaded() || !hasBossBarVisible(p) || playersInDisabledWorlds.contains(p)) continue;
				for (BossBar line : lines.values()) {
					if (line.getPlayers().contains(p) && !((BossBarLine) line).isConditionMet(p)) {
						line.removePlayer(p);
					}
				}
				showBossBars(p, defaultBars);
				showBossBars(p, perWorld.get(TAB.getInstance().getConfiguration().getWorldGroupOf(perWorld.keySet(), p.getWorldName())));
			}
		});
	}

	@Override
	public void unload() {
		for (BossBar line : lines.values()) {
			for (TabPlayer p : TAB.getInstance().getPlayers()) {
				line.removePlayer(p);
			}
		}
	}

	@Override
	public void onJoin(TabPlayer connectedPlayer) {
		if (isDisabledWorld(disabledWorlds, connectedPlayer.getWorldName())) {
			playersInDisabledWorlds.add(connectedPlayer);
			return;
		}
		setBossBarVisible(connectedPlayer, !bossbarOffPlayers.contains(connectedPlayer.getName()) && !hiddenByDefault, false);
	}

	@Override
	public void onWorldChange(TabPlayer p, String from, String to) {
		if (isDisabledWorld(disabledWorlds, p.getWorldName())) {
			playersInDisabledWorlds.add(p);
		} else {
			playersInDisabledWorlds.remove(p);
		}
		for (BossBar line : lines.values()) {
			line.removePlayer(p);
		}
		detectBossBarsAndSend(p);
	}

	@Override
	public boolean onCommand(TabPlayer sender, String message) {
		if (message.equalsIgnoreCase(toggleCommand)) {
			TAB.getInstance().getCommand().execute(sender, new String[] {"bossbar"});
			return true;
		}
		return false;
	}

	/**
	 * Clears and resends all bossbars to specified player
	 * @param p - player to process
	 */
	protected void detectBossBarsAndSend(TabPlayer p) {
		if (playersInDisabledWorlds.contains(p) || !hasBossBarVisible(p)) return;
		showBossBars(p, defaultBars);
		showBossBars(p, announcements.stream().map(BossBar::getName).collect(Collectors.toList()));
		showBossBars(p, perWorld.get(TAB.getInstance().getConfiguration().getWorldGroupOf(perWorld.keySet(), p.getWorldName())));
	}

	/**
	 * Shows bossbars to player if display condition is met
	 * @param p - player to show bossbars to
	 * @param bars - list of bossbars to check
	 */
	private void showBossBars(TabPlayer p, List<String> bars) {
		if (bars == null) return;
		for (String defaultBar : bars) {
			BossBarLine bar = (BossBarLine) lines.get(defaultBar);
			if (bar.isConditionMet(p) && !bar.getPlayers().contains(p)) {
				bar.addPlayer(p);
			}
		}
	}

	@Override
	public String getFeatureType() {
		return "Bossbar";
	}

	@Override
	public void onQuit(TabPlayer disconnectedPlayer) {
		playersInDisabledWorlds.remove(disconnectedPlayer);
		visiblePlayers.remove(disconnectedPlayer);
		for (BossBar line : lines.values()) {
			line.removePlayer(disconnectedPlayer);
		}
	}

	@Override
	public BossBar createBossBar(String name, String title, float progress, BarColor color, BarStyle style) {
		return createBossBar(name, title, String.valueOf(progress), color.toString(), style.toString());
	}

	@Override
	public BossBar createBossBar(String name, String title, String progress, String color, String style) {
		BossBar bar = new BossBarLine(name, null, color, style, title, progress);
		lines.put(bar.getName(), (BossBarLine) bar);
		return bar;
	}

	@Override
	public BossBar getBossBar(String name) {
		return lines.get(name);
	}

	@Override
	public BossBar getBossBar(UUID id) {
		for (BossBar line : lines.values()) {
			if (line.getUniqueId() == id) return line;
		}
		return null;
	}

	@Override
	public void toggleBossBar(TabPlayer player, boolean sendToggleMessage) {
		setBossBarVisible(player, !hasBossBarVisible(player), sendToggleMessage);
	}

	@Override
	public Map<String, BossBar> getRegisteredBossBars() {
		return lines;
	}

	@Override
	public boolean hasBossBarVisible(TabPlayer player) {
		return visiblePlayers.contains(player);
	}

	@Override
	public void setBossBarVisible(TabPlayer player, boolean visible, boolean sendToggleMessage) {
		if (visiblePlayers.contains(player) == visible) return;
		if (visible) {
			visiblePlayers.add(player);
			detectBossBarsAndSend(player);
			if (sendToggleMessage) player.sendMessage(TAB.getInstance().getConfiguration().getTranslation().getString("bossbar-toggle-on"), true);
			if (rememberToggleChoice) {
				bossbarOffPlayers.remove(player.getName());
				TAB.getInstance().getConfiguration().getPlayerDataFile().set("bossbar-off", bossbarOffPlayers);
			}
		} else {
			visiblePlayers.remove(player);
			lines.values().forEach(l -> l.removePlayer(player));
			if (sendToggleMessage) player.sendMessage(TAB.getInstance().getConfiguration().getTranslation().getString("bossbar-toggle-off"), true);
			if (rememberToggleChoice && !bossbarOffPlayers.contains(player.getName())) {
				bossbarOffPlayers.add(player.getName());
				TAB.getInstance().getConfiguration().getPlayerDataFile().set("bossbar-off", bossbarOffPlayers);
			}
		}
	}

	@Override
	public void sendBossBarTemporarily(TabPlayer player, String bossbar, int duration) {
		if (!hasBossBarVisible(player)) return;
		BossBar line = lines.get(bossbar);
		if (line == null) throw new IllegalArgumentException("No registered bossbar found with name " + bossbar);
		new Thread(() -> {
			try {
				line.addPlayer(player);
				Thread.sleep(duration*1000L);
				line.removePlayer(player);
			} catch (InterruptedException pluginDisabled) {
				Thread.currentThread().interrupt();
			}
		}).start();
	}

	@Override
	public void announceBossBar(String bossbar, int duration) {
		BossBar line = lines.get(bossbar);
		if (line == null) throw new IllegalArgumentException("No registered bossbar found with name " + bossbar);
		new Thread(() -> {
			try {
				announcements.add(line);
				announceEndTime = (System.currentTimeMillis() + duration*1000);
				for (TabPlayer all : TAB.getInstance().getPlayers()) {
					if (!hasBossBarVisible(all)) continue;
					line.addPlayer(all);
				}
				Thread.sleep(duration*1000L);
				for (TabPlayer all : TAB.getInstance().getPlayers()) {
					if (!hasBossBarVisible(all)) continue;
					line.removePlayer(all);
				}
				announcements.remove(line);
			} catch (InterruptedException pluginDisabled) {
				Thread.currentThread().interrupt();
			}
		}).start();
	}

	@Override
	public Set<BossBar> getAnnouncedBossBars() {
		return announcements;
	}
}