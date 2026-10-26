package ly.alwaha.card;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The live set of components built from one Config. "Save & apply" in the dashboard builds a new set from the
 * saved file and swaps it in under the same lock the endpoint uses, so a request never sees half a config.
 * If the new config can't be built, the old one stays in place.
 */
public final class Service {

    public record Components(Config cfg, CardRenderer renderer, AuditLog audit, CustomerLookup lookup,
                             Quarantine quarantine, Generator generator, Dispatcher dispatcher,
                             SourceImages source, RequestStore store) {}

    private final Path configFile;
    private final Secrets secrets;
    private final Object lock = new Object();
    private volatile Components current;

    /** configFile and secrets may be null when the config isn't file-backed (then reload isn't possible). */
    public Service(Config cfg, Path configFile, Secrets secrets) throws IOException {
        this.configFile = configFile;
        this.secrets = secrets;
        this.current = build(cfg);
    }

    public static Components build(Config cfg) throws IOException {
        CardRenderer renderer = new CardRenderer(cfg.templateDir(), cfg.fontRegular(), cfg.fontBold());
        AuditLog audit = new AuditLog(cfg.archive().resolve("dispatch.log"));
        CustomerLookup lookup = new CustomerLookup(cfg.api());
        // No quarantine key yet: the endpoint answers "not configured" instead of handling files it can't quarantine.
        Quarantine quarantine = cfg.quarantineKey() == null || cfg.quarantineKey().isBlank()
                ? null : new Quarantine(cfg.quarantine(), cfg.quarantineKey());
        return new Components(cfg, renderer, audit, lookup, quarantine,
                new Generator(cfg, renderer, audit, quarantine),
                new Dispatcher(cfg, audit),
                new SourceImages(cfg.source(), cfg.limits(), quarantine),
                new RequestStore(cfg.requests()));
    }

    public Components current() {
        return current;
    }

    public Object lock() {
        return lock;
    }

    public Path configFile() {
        return configFile;
    }

    public Secrets secrets() {
        return secrets;
    }

    /** Re-reads the config file and swaps the components in. Throws (and keeps the old ones) if it's invalid. */
    public void reload() throws IOException {
        Components next = build(Config.load(configFile, secrets));
        synchronized (lock) {
            current = next;
        }
    }
}
