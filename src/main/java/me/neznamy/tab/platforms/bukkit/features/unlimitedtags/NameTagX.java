package me.neznamy.tab.platforms.bukkit.features.unlimitedtags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.common.collect.Lists;

import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.premium.Premium;
import me.neznamy.tab.premium.SortingType;
import me.neznamy.tab.shared.ITabPlayer;
import me.neznamy.tab.shared.Property;
import me.neznamy.tab.shared.ProtocolVersion;
import me.neznamy.tab.shared.Shared;
import me.neznamy.tab.shared.config.Configs;
import me.neznamy.tab.shared.cpu.TabFeature;
import me.neznamy.tab.shared.cpu.UsageType;
import me.neznamy.tab.shared.features.interfaces.JoinEventListener;
import me.neznamy.tab.shared.features.interfaces.Loadable;
import me.neznamy.tab.shared.features.interfaces.QuitEventListener;
import me.neznamy.tab.shared.features.interfaces.Refreshable;
import me.neznamy.tab.shared.features.interfaces.WorldChangeListener;

/**
 * The core class for unlimited nametag mode
 */
public class NameTagX implements Loadable, JoinEventListener, QuitEventListener, WorldChangeListener, Refreshable{

	private JavaPlugin plugin;
	public boolean markerFor18x;
	private Set<String> usedPlaceholders;
	public List<String> dynamicLines = Arrays.asList("belowname", "nametag", "abovename");
	public Map<String, Object> staticLines = new ConcurrentHashMap<String, Object>();

	public Map<Integer, List<Integer>> vehicles = new ConcurrentHashMap<>();
	public Map<ITabPlayer, List<TabPlayer>> delayedSpawn = new HashMap<ITabPlayer, List<TabPlayer>>();
	private EventListener eventListener;

	@SuppressWarnings("unchecked")
	public NameTagX(JavaPlugin plugin) {
		this.plugin = plugin;
		markerFor18x = Configs.config.getBoolean("unlimited-nametag-prefix-suffix-mode.use-marker-tag-for-1-8-x-clients", false);
		if (Premium.is()) {
			List<String> realList = Premium.premiumconfig.getStringList("unlimited-nametag-mode-dynamic-lines", Arrays.asList("abovename", "nametag", "belowname", "another"));
			dynamicLines = new ArrayList<String>();
			dynamicLines.addAll(realList);
			Collections.reverse(dynamicLines);
			staticLines = Premium.premiumconfig.getConfigurationSection("unlimited-nametag-mode-static-lines");
		}
		refreshUsedPlaceholders();
		eventListener = new EventListener();
		Shared.featureManager.registerFeature("nametagx-packet", new PacketListener(this));
	}
	
	@Override
	public void load() {
		Bukkit.getPluginManager().registerEvents(eventListener, plugin);
		for (ITabPlayer all : Shared.getPlayers()){
			all.teamName = SortingType.INSTANCE.getTeamName(all);
			updateProperties(all);
			if (all.disabledNametag) continue;
			all.registerTeam();
			loadArmorStands(all);
			if (all.getBukkitEntity().getVehicle() != null) {
				Entity vehicle = all.getBukkitEntity().getVehicle();
				List<Integer> list = new ArrayList<Integer>();
				for (Entity e : getPassengers(vehicle)) {
					list.add(e.getEntityId());
				}
				vehicles.put(vehicle.getEntityId(), list);
			}
			for (ITabPlayer worldPlayer : Shared.getPlayers()) {
				if (all == worldPlayer) continue;
				if (!worldPlayer.getWorldName().equals(all.getWorldName())) continue;
				all.getArmorStandManager().spawn(worldPlayer);
			}
		}
		Shared.cpu.startRepeatingMeasuredTask(200, "refreshing nametag visibility", getFeatureType(), UsageType.REPEATING_TASK, new Runnable() {
			public void run() {
				for (ITabPlayer p : Shared.getPlayers()) {
					if (!p.onJoinFinished || p.disabledNametag) continue;
					p.getArmorStandManager().updateVisibility();
				}
			}
		});
		Shared.cpu.startRepeatingMeasuredTask(200, "refreshing collision", getFeatureType(), UsageType.REPEATING_TASK, new Runnable() {
			public void run() {
				for (ITabPlayer p : Shared.getPlayers()) {
					if (!p.onJoinFinished || p.disabledNametag) continue;
					boolean collision = p.getTeamPush();
					if (p.lastCollision != collision) {
						p.updateTeamData();
					}
				}
			}
		});
	}
	
	@Override
	public void unload() {
		HandlerList.unregisterAll(eventListener);
		for (ITabPlayer p : Shared.getPlayers()) {
			if (!p.disabledNametag) p.unregisterTeam();
			p.getArmorStandManager().destroy();
		}
	}
	
	@Override
	public void onJoin(ITabPlayer connectedPlayer) {
		connectedPlayer.teamName = SortingType.INSTANCE.getTeamName(connectedPlayer);
		updateProperties(connectedPlayer);
		for (ITabPlayer all : Shared.getPlayers()) {
			if (all == connectedPlayer) continue;
			if (!all.disabledNametag) all.registerTeam(connectedPlayer);
		}
		if (connectedPlayer.disabledNametag) return;
		connectedPlayer.registerTeam();
		loadArmorStands(connectedPlayer);
		if (connectedPlayer.getBukkitEntity().getVehicle() != null) {
			Entity vehicle = connectedPlayer.getBukkitEntity().getVehicle();
			List<Integer> list = new ArrayList<Integer>();
			for (Entity e : getPassengers(vehicle)) {
				list.add(e.getEntityId());
			}
			vehicles.put(vehicle.getEntityId(), list);
		}
		if (delayedSpawn.containsKey(connectedPlayer)) {
			for (TabPlayer viewer : delayedSpawn.get(connectedPlayer)) {
				connectedPlayer.getArmorStandManager().spawn(viewer);
			}
			delayedSpawn.remove(connectedPlayer);
		}
	}
	
	@Override
	public void onQuit(ITabPlayer disconnectedPlayer) {
		if (!disconnectedPlayer.disabledNametag) disconnectedPlayer.unregisterTeam();
		Shared.cpu.runTaskLater(100, "Processing player quit", getFeatureType(), UsageType.PLAYER_QUIT_EVENT, new Runnable() {

			@Override
			public void run() {
				for (ITabPlayer all : Shared.getPlayers()) {
					all.getArmorStandManager().unregisterPlayer(disconnectedPlayer);
				}
				disconnectedPlayer.getArmorStandManager().destroy();
			}
		});
	}
	
	@Override
	public void onWorldChange(ITabPlayer p, String from, String to) {
		updateProperties(p);
		if (p.disabledNametag && !p.isDisabledWorld(Configs.disabledNametag, from)) {
			p.unregisterTeam();
		} else if (!p.disabledNametag && p.isDisabledWorld(Configs.disabledNametag, from)) {
			p.registerTeam();
		} else {
			p.updateTeam();
			p.getArmorStandManager().refresh();
			fixArmorStandHeights(p);
		}
	}
	
	public void loadArmorStands(ITabPlayer pl) {
		pl.setArmorStandManager(new ArmorStandManager());
		pl.setProperty("nametag", pl.getProperty("tagprefix").getCurrentRawValue() + pl.getProperty("customtagname").getCurrentRawValue() + pl.getProperty("tagsuffix").getCurrentRawValue(), null);
		double height = -Configs.SECRET_NTX_space;
		for (String line : dynamicLines) {
			Property p = pl.getProperty(line);
			pl.getArmorStandManager().addArmorStand(line, new ArmorStand(pl, p, height+=Configs.SECRET_NTX_space, false));
		}
		for (Entry<String, Object> line : staticLines.entrySet()) {
			Property p = pl.getProperty(line.getKey());
			pl.getArmorStandManager().addArmorStand(line.getKey(), new ArmorStand(pl, p, Double.parseDouble(line.getValue()+""), true));
		}
		fixArmorStandHeights(pl);
	}
	
	public void fixArmorStandHeights(ITabPlayer p) {
		p.getArmorStandManager().refresh();
		double currentY = -Configs.SECRET_NTX_space;
		for (ArmorStand as : p.getArmorStandManager().getArmorStands()) {
			if (as.hasStaticOffset()) continue;
			if (as.property.get().length() != 0) {
				currentY += Configs.SECRET_NTX_space;
				as.setOffset(currentY);
			}
		}
	}

	@Override
	public void refresh(ITabPlayer refreshed, boolean force) {
		if (refreshed.disabledNametag) return;
		boolean refresh;
		if (force) {
			updateProperties(refreshed);
			refresh = true;
		} else {
			boolean prefix = refreshed.getProperty("tagprefix").update();
			boolean suffix = refreshed.getProperty("tagsuffix").update();
			refresh = prefix || suffix;
		}
		if (refresh) refreshed.updateTeam();
		boolean fix = false;
		for (ArmorStand as : refreshed.getArmorStandManager().getArmorStands()) {
			if (as.property.update() || force) {
				as.refresh();
				fix = true;
			}
		}
		if (fix) fixArmorStandHeights(refreshed);
	}
	
	private void updateProperties(ITabPlayer p) {
		p.updateProperty("tagprefix");
		p.updateProperty("customtagname", p.getName());
		p.updateProperty("tagsuffix");
		p.setProperty("nametag", p.getProperty("tagprefix").getCurrentRawValue() + p.getProperty("customtagname").getCurrentRawValue() + p.getProperty("tagsuffix").getCurrentRawValue(), null);
		for (String property : dynamicLines) {
			if (!property.equals("nametag")) p.updateProperty(property);
		}
		for (String property : staticLines.keySet()) {
			if (!property.equals("nametag")) p.updateProperty(property);
		}
	}
	
	@Override
	public Set<String> getUsedPlaceholders() {
		return usedPlaceholders;
	}
	
	@SuppressWarnings("deprecation")
	public List<Entity> getPassengers(Entity vehicle){
		if (ProtocolVersion.SERVER_VERSION.getMinorVersion() >= 11) {
			return vehicle.getPassengers();
		} else {
			if (vehicle.getPassenger() != null) {
				return Lists.newArrayList(vehicle.getPassenger()); 
			} else {
				return new ArrayList<Entity>();
			}
		}
	}
	
	@Override
	public void refreshUsedPlaceholders() {
		usedPlaceholders = Configs.config.getUsedPlaceholderIdentifiersRecursive("tagprefix", "customtagname", "tagsuffix");
		for (String line : dynamicLines) {
			usedPlaceholders.addAll(Configs.config.getUsedPlaceholderIdentifiersRecursive(line));
		}
		for (String line : staticLines.keySet()) {
			usedPlaceholders.addAll(Configs.config.getUsedPlaceholderIdentifiersRecursive(line));
		}
	}

	@Override
	public TabFeature getFeatureType() {
		return TabFeature.NAMETAGX;
	}
}