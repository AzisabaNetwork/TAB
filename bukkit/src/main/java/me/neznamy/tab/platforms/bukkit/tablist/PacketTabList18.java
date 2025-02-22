package me.neznamy.tab.platforms.bukkit.tablist;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import lombok.SneakyThrows;
import me.neznamy.tab.platforms.bukkit.BukkitTabPlayer;
import me.neznamy.tab.platforms.bukkit.BukkitUtils;
import me.neznamy.tab.platforms.bukkit.nms.BukkitReflection;
import me.neznamy.tab.platforms.bukkit.nms.PacketSender;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.chat.TabComponent;
import me.neznamy.tab.shared.platform.TabList;
import me.neznamy.tab.shared.util.ComponentCache;
import me.neznamy.tab.shared.util.ReflectionUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * TabList handler for 1.8 - 1.19.2 servers using packets.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class PacketTabList18 extends TabListBase {

    protected static Class<?> PlayerInfoClass;
    protected static Constructor<?> newPlayerInfo;
    protected static Field ACTION;
    protected static Field PLAYERS;
    protected static Class<Enum> ActionClass;

    protected static Constructor<?> newPlayerInfoData;
    protected static Field PlayerInfoData_Profile;
    protected static Field PlayerInfoData_Latency;
    protected static Field PlayerInfoData_DisplayName;

    protected static Object[] gameModes;

    protected static Method ChatSerializer_DESERIALIZE;
    private static final ComponentCache<TabComponent, Object> componentCache = new ComponentCache<>(1000,
            (component, clientVersion) -> ChatSerializer_DESERIALIZE.invoke(null, component.toString(clientVersion)));

    protected static PacketSender packetSender;

    /**
     * Constructs new instance with given player.
     *
     * @param   player
     *          Player this tablist will belong to.
     */
    public PacketTabList18(@NotNull BukkitTabPlayer player) {
        super(player);
    }

    /**
     * Attempts to load all required NMS classes, fields and methods.
     * If anything fails, throws an exception.
     *
     * @throws  ReflectiveOperationException
     *          If something goes wrong
     */
    public static void load() throws ReflectiveOperationException {
        Class<?> ChatSerializer = BukkitReflection.getClass("network.chat.Component$Serializer",
                "network.chat.IChatBaseComponent$ChatSerializer", "IChatBaseComponent$ChatSerializer", "ChatSerializer");
        ChatSerializer_DESERIALIZE = ReflectionUtils.getMethods(ChatSerializer, Object.class, String.class).get(0);
        Class<?> IChatBaseComponent = BukkitReflection.getClass("network.chat.Component", "network.chat.IChatBaseComponent", "IChatBaseComponent");
        Class<Enum> EnumGamemodeClass = (Class<Enum>) BukkitReflection.getClass("world.level.GameType",
                "world.level.EnumGamemode", "EnumGamemode", "WorldSettings$EnumGamemode");
        ActionClass = (Class<Enum>) BukkitReflection.getClass(
                "network.protocol.game.ClientboundPlayerInfoPacket$Action", // Mojang 1.17 - 1.19.2
                "network.protocol.game.PacketPlayOutPlayerInfo$EnumPlayerInfoAction", // Bukkit 1.17 - 1.19.2
                "PacketPlayOutPlayerInfo$EnumPlayerInfoAction", // Bukkit 1.8.1 - 1.16.5
                "EnumPlayerInfoAction" // Bukkit 1.8.0
        );
        PlayerInfoClass = BukkitReflection.getClass("network.protocol.game.ClientboundPlayerInfoUpdatePacket",
                "network.protocol.game.ClientboundPlayerInfoPacket",
                "network.protocol.game.PacketPlayOutPlayerInfo", "PacketPlayOutPlayerInfo");
        Class<?> playerInfoDataClass = BukkitReflection.getClass(
                "network.protocol.game.ClientboundPlayerInfoPacket$PlayerUpdate", // Mojang 1.17 - 1.19.2
                "network.protocol.game.PacketPlayOutPlayerInfo$PlayerInfoData", // Bukkit 1.17 - 1.19.2
                "PacketPlayOutPlayerInfo$PlayerInfoData", // Bukkit 1.8.1 - 1.16.5
                "PlayerInfoData" // Bukkit 1.8.0
        );

        Class<?> classType = BukkitReflection.getMinorVersion() >= 17 ? Collection.class : Iterable.class;
        newPlayerInfo = PlayerInfoClass.getConstructor(ActionClass, classType);
        ACTION = ReflectionUtils.getOnlyField(PlayerInfoClass, ActionClass);

        loadSharedContent(playerInfoDataClass, EnumGamemodeClass, IChatBaseComponent);
    }

    protected static void loadSharedContent(Class<?> infoData, Class<Enum> gameMode,
                                            Class<?> component) throws ReflectiveOperationException {
        newPlayerInfoData = infoData.getConstructors()[0]; // #1105, a specific 1.8.8 fork has 2 constructors
        PLAYERS = ReflectionUtils.getOnlyField(PlayerInfoClass, List.class);
        PlayerInfoData_Profile = ReflectionUtils.getOnlyField(infoData, GameProfile.class);
        PlayerInfoData_Latency = ReflectionUtils.getOnlyField(infoData, int.class);
        PlayerInfoData_DisplayName = ReflectionUtils.getOnlyField(infoData, component);
        gameModes = new Object[] {
                Enum.valueOf(gameMode, "SURVIVAL"),
                Enum.valueOf(gameMode, "CREATIVE"),
                Enum.valueOf(gameMode, "ADVENTURE"),
                Enum.valueOf(gameMode, "SPECTATOR")
        };
        packetSender = new PacketSender();
        try {
            skinData = new SkinData();
        } catch (Exception e) {
            BukkitUtils.compatibilityError(e, "getting player's game profile", null,
                    "Player skins not working in layout feature");
        }
    }

    @Override
    public void removeEntry(@NotNull UUID entry) {
        packetSender.sendPacket(player.getPlayer(), createPacket(Action.REMOVE_PLAYER, new Entry(entry)));
    }

    @Override
    public void updateDisplayName(@NotNull UUID entry, @Nullable TabComponent displayName) {
        packetSender.sendPacket(player.getPlayer(), createPacket(Action.UPDATE_DISPLAY_NAME, Entry.displayName(entry, displayName)));
    }

    @Override
    public void updateLatency(@NotNull UUID entry, int latency) {
        packetSender.sendPacket(player.getPlayer(), createPacket(Action.UPDATE_LATENCY, Entry.latency(entry, latency)));
    }

    @Override
    public void updateGameMode(@NotNull UUID entry, int gameMode) {
        packetSender.sendPacket(player.getPlayer(), createPacket(Action.UPDATE_GAME_MODE, Entry.gameMode(entry, gameMode)));
    }

    @Override
    public void addEntry(@NotNull Entry entry) {
        packetSender.sendPacket(player.getPlayer(), createPacket(Action.ADD_PLAYER, entry));

        if (player.getVersion().getMinorVersion() == 8) {
            // Compensation for 1.8.0 client sided bug
            updateDisplayName(entry.getUniqueId(), entry.getDisplayName());
        }
    }

    @SneakyThrows
    @NotNull
    public Object createPacket(@NotNull Action action, @NotNull Entry entry) {
        List<Object> players = new ArrayList<>();
        Object packet = newPlayerInfo.newInstance(Enum.valueOf(ActionClass, action.name()), Collections.emptyList());
        List<Object> parameters = new ArrayList<>();
        if (newPlayerInfoData.getParameterTypes()[0] == PlayerInfoClass) {
            parameters.add(packet);
        }
        parameters.add(createProfile(entry.getUniqueId(), entry.getName(), entry.getSkin()));
        parameters.add(entry.getLatency());
        parameters.add(gameModes[entry.getGameMode()]);
        parameters.add(entry.getDisplayName() == null ? null : toComponent(entry.getDisplayName()));
        if (BukkitReflection.getMinorVersion() >= 19) parameters.add(null);
        players.add(newPlayerInfoData.newInstance(parameters.toArray()));
        PLAYERS.set(packet, players);
        return packet;
    }

    /**
     * Converts TAB component into NMS component.
     *
     * @param   component
     *          Component to convert
     * @return  Converted component
     */
    public Object toComponent(@NotNull TabComponent component) {
        return componentCache.get(component, player.getVersion());
    }

    @NotNull
    public GameProfile createProfile(@NotNull UUID id, @Nullable String name, @Nullable Skin skin) {
        GameProfile profile = new GameProfile(id, name == null ? "" : name);
        if (skin != null) {
            profile.getProperties().put(TabList.TEXTURES_PROPERTY,
                    new Property(TabList.TEXTURES_PROPERTY, skin.getValue(), skin.getSignature()));
        }
        return profile;
    }

    @Override
    @SneakyThrows
    public void onPacketSend(@NotNull Object packet) {
        if (!(PlayerInfoClass.isInstance(packet))) return;
        String action = ACTION.get(packet).toString();
        for (Object nmsData : (List<?>) PLAYERS.get(packet)) {
            GameProfile profile = (GameProfile) PlayerInfoData_Profile.get(nmsData);
            UUID id = profile.getId();
            if (action.equals(Action.UPDATE_DISPLAY_NAME.name()) || action.equals(Action.ADD_PLAYER.name())) {
                Object displayName = PlayerInfoData_DisplayName.get(nmsData);
                TabComponent newDisplayName = TAB.getInstance().getFeatureManager().onDisplayNameChange(player, id);
                if (newDisplayName != null) displayName = toComponent(newDisplayName);
                PlayerInfoData_DisplayName.set(nmsData, displayName);
            }
            if (action.equals(Action.UPDATE_LATENCY.name()) || action.equals(Action.ADD_PLAYER.name())) {
                int latency = TAB.getInstance().getFeatureManager().onLatencyChange(player, id, PlayerInfoData_Latency.getInt(nmsData));
                PlayerInfoData_Latency.set(nmsData, latency);
            }
            if (action.equals(Action.ADD_PLAYER.name())) {
                TAB.getInstance().getFeatureManager().onEntryAdd(player, id, profile.getName());
            }
        }
    }
}
