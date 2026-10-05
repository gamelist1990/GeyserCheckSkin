package com.pexserver.checkskin;

import org.geysermc.event.PostOrder;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.*;
import org.geysermc.geyser.api.event.lifecycle.*;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.session.GeyserSession;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class CheckSkinExtension implements Extension {
    private volatile State state;
    private final Map<GeyserConnection, FallbackSkin> replaced = new ConcurrentHashMap<>();

    private record State(CheckConfig config, SkinInspector inspector, List<FallbackSkin> fallbacks,
            BedrockSkinAdapter adapter) {
    }

    @Subscribe
    public void initialize(GeyserPreInitializeEvent event) {
        try {
            CheckConfig config = CheckConfig.load(dataFolder());
            if (!config.enabled) {
                state = new State(config, null, null, null);
                logger().info("CheckSkin disabled by config.json");
                return;
            }
            state = new State(config, new SkinInspector(config), FallbackSkin.loadAll(dataFolder(), config),
                    new BedrockSkinAdapter());
            logger().info("CheckSkin ready: geometry=" + config.geometryMode + ", action=" + config.violationAction
                    + ", fallbacks=" + state.fallbacks.size());
        } catch (Exception | LinkageError e) {
            logger().error("CheckSkin initialization failed; logins will be blocked: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    @Subscribe(postOrder = PostOrder.LAST)
    public void login(SessionLoginEvent event) {
        State current = state;
        if (current == null) {
            event.setCancelled(true, "スキン検査の初期化に失敗しています。管理者へ連絡してください。");
            return;
        }
        if (!current.config.enabled || event.isCancelled())
            return;
        if (!(event.connection() instanceof GeyserSession session)) {
            event.setCancelled(true, "このGeyserバージョンではスキン検査を利用できません。");
            return;
        }
        SkinInspector.Result result;
        try {
            result = current.inspector.inspect(current.adapter.read(session.getClientData()));
        } catch (Exception | LinkageError e) {
            logger().error("Could not inspect login skin: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            event.setCancelled(true, current.config.kickMessage);
            return;
        }
        if (result.allowed())
            return;
        if (current.config.logViolations)
            logger().warning("Rejected skin: player=" + session.bedrockUsername()
                    + ", reason=" + result.reason() + ", action=" + current.config.violationAction);
        if (current.config.violationAction == CheckConfig.Action.KICK) {
            event.setCancelled(true, current.config.kickMessage);
            return;
        }
        try {
            FallbackSkin chosen = current.fallbacks.get(ThreadLocalRandom.current().nextInt(current.fallbacks.size()));
            current.adapter.replace(session.getClientData(), chosen);
            replaced.put(session, chosen);
        } catch (Exception | LinkageError e) {
            logger().error("Could not replace rejected skin: " + e.getMessage());
            event.setCancelled(true, current.config.kickMessage);
        }
    }

    @Subscribe(postOrder = PostOrder.LAST)
    public void apply(SessionSkinApplyEvent event) {
        State current = state;
        if (current == null || !current.config.enabled)
            return;
        GeyserConnection subject = GeyserApi.api().connectionByUuid(event.uuid());
        FallbackSkin chosen = subject == null ? null : replaced.get(subject);
        if (chosen != null) {
            var data = chosen.data();
            event.skin(data.skin());
            event.geometry(data.geometry());
            event.cape(data.cape());
        }
    }

    @Subscribe
    public void disconnect(SessionDisconnectEvent event) {
        replaced.remove(event.connection());
    }

    @Subscribe
    public void shutdown(GeyserShutdownEvent event) {
        replaced.clear();
        state = null;
    }
}
