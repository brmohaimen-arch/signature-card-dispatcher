package ly.alwaha.card;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * java -jar signature-card-dispatcher.jar [--config FILE] COMMAND
 *
 *   serve                    the endpoint for the onboarding app + dispatch every poll.seconds
 *   run                      generate + dispatch every poll.seconds, forever (folder input)
 *   once                     one generate + dispatch cycle, then exit
 *   render INPUT_DIR OUT.jpg look up + render one input folder to a file (no archive, no send)
 *   calibrate OUT.jpg        draw every layout.json box over the template
 *   check-sftp               connect + log in to T24 SFTP and check the folder (uploads nothing)
 *   placeholder              write a stand-in template.png from layout.json (development only)
 */
public final class App {

    public static void main(String[] args) throws Exception {
        Path configFile = Path.of("config/application.properties");
        int i = 0;
        if (args.length >= 2 && args[0].equals("--config")) {
            configFile = Path.of(args[1]);
            i = 2;
        }
        if (args.length <= i) {
            usage();
            return;
        }
        Secrets secrets = Secrets.forConfig(configFile);
        Config cfg = Config.load(configFile, secrets);
        String command = args[i];

        if (command.equals("check-sftp")) {
            System.out.println("connecting to " + cfg.sftpUser() + "@" + cfg.sftpHost() + ":" + cfg.sftpPort()
                    + " (host key from " + cfg.sftpKnownHosts() + ", "
                    + (cfg.sftpKeyPath() != null ? "key " + cfg.sftpKeyPath() : "password from SFTP_PASSWORD") + ")");
            if (cfg.sftpKeyPath() == null && cfg.sftpPassword() == null) {
                System.out.println("SFTP_PASSWORD is not set in this terminal");
                System.exit(1);
            }
            try (SftpConnection up = SftpConnection.connect(cfg.t24Sftp())) {
                System.out.println("OK: logged in; target folder is " + up.describeRemoteDir() + " (nothing was uploaded)");
            } catch (Exception e) {
                System.out.println("FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        if (command.equals("check-source-sftp")) {
            Config.SftpEndpoint src = cfg.source().sftp();
            System.out.println("connecting to " + src.user() + "@" + src.host() + ":" + src.port()
                    + " (host key from " + src.knownHosts() + ")");
            try (SftpConnection c = SftpConnection.connect(src)) {
                System.out.println("OK: logged in to the onboarding server (nothing was read, written or deleted)");
            } catch (Exception e) {
                System.out.println("FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        if (command.equals("decrypt-quarantine")) {
            new Quarantine(cfg.quarantine(), cfg.quarantineKey()).decrypt(Path.of(args[i + 1]), Path.of(args[i + 2]));
            System.out.println("decrypted to " + args[i + 2] + " (do NOT open it outside an isolated machine)");
            return;
        }

        if (command.equals("check-api")) {
            if (args.length < i + 4) {
                System.out.println("usage: check-api NATIONAL_ID PASSPORT_NUMBER PHONE_NUMBER   (a TEST customer)");
                System.exit(1);
            }
            if (!new CustomerLookup(cfg.api()).configured()) {
                System.out.println("onboarding API is not configured (api.base.url / api.cif.path)");
                System.exit(1);
            }
            System.out.println("calling " + cfg.api().baseUrl());
            System.out.println(new CustomerLookup(cfg.api()).probe(
                    new CustomerLookup.Identity(args[i + 1], args[i + 2], args[i + 3])));
            return;
        }

        if (command.equals("placeholder")) {
            Layout layout = Layout.load(cfg.templateDir().resolve("layout.json"));
            Path out = cfg.templateDir().resolve("template.png");
            ImageIO.write(TemplateTools.placeholder(layout), "png", out.toFile());
            System.out.println("wrote " + out);
            return;
        }

        CardRenderer renderer = new CardRenderer(cfg.templateDir(), cfg.fontRegular(), cfg.fontBold());
        AuditLog audit = new AuditLog(cfg.archive().resolve("dispatch.log"));

        switch (command) {
            case "calibrate" -> {
                Path out = Path.of(args[i + 1]);
                Files.write(out, JpegWriter.write(TemplateTools.calibration(renderer.template(), renderer.layout()),
                        cfg.jpegQuality(), renderer.layout().dpi()));
                System.out.println("wrote " + out);
            }
            case "render" -> {
                CustomerLookup lookup = new CustomerLookup(cfg.api());
                FolderInput.InputSet in = FolderInput.read(Path.of(args[i + 1]));
                byte[] jpg = new Generator(cfg, renderer, audit, lookup, null).renderJpeg(lookup.lookup(in.identity()),
                        ImageLoader.load(in.selfie(), cfg.limits()), ImageLoader.load(in.signature(), cfg.limits()));
                Path out = Path.of(args[i + 2]);
                Files.write(out, jpg);
                System.out.println("wrote " + out + " (" + jpg.length + " bytes)");
            }
            case "once", "run", "serve" -> {
                refusePlaceholder(cfg, renderer.layout());
                if (!command.equals("serve") && cfg.quarantineKey() == null) {
                    throw new IllegalStateException("QUARANTINE_KEY is not set");
                }
                Service service = new Service(cfg, configFile, secrets);
                boolean loop = !command.equals("once");
                if (command.equals("serve")) {
                    String password = System.getenv("DASHBOARD_PASSWORD");
                    AdminApi admin = password == null ? null : new AdminApi(service, password);
                    ApiServer api = new ApiServer(service, admin);
                    api.start();
                    String base = "http://" + cfg.server().bind() + ":" + api.port();
                    System.out.println("endpoint:  " + base + ApiServer.BASE);
                    System.out.println("dashboard: " + (admin == null ? "disabled (set DASHBOARD_PASSWORD to enable)" : base + "/dashboard"));
                }
                do {
                    Service.Components c = service.current(); // picks up "Save & apply" from the dashboard
                    try {
                        if (c.quarantine() != null) c.generator().processInbox();
                        c.dispatcher().dispatchOutbox();
                    } catch (Exception e) {
                        c.audit().log("ERROR", null, "reason=\"" + e + "\"");
                    }
                    if (loop) Thread.sleep(c.cfg().pollSeconds() * 1000L);
                } while (loop);
            }
            default -> usage();
        }
    }

    /** Cards made on the stand-in template must never reach T24. */
    private static void refusePlaceholder(Config cfg, Layout layout) {
        if (layout.templateId().contains("PLACEHOLDER") && !cfg.allowPlaceholderTemplate()) {
            throw new IllegalStateException("template " + layout.templateId()
                    + " is a placeholder; install the official template (or set allow.placeholder.template=true for testing only)");
        }
    }

    private static void usage() {
        System.out.println("""
                usage: java -jar signature-card-dispatcher.jar [--config FILE] COMMAND
                  serve                     endpoint for the onboarding app + dispatch every poll.seconds
                  run                       generate + dispatch every poll.seconds, forever (folder input)
                  once                      one generate + dispatch cycle, then exit
                  render INPUT_DIR OUT.jpg  render one input folder to a file (no archive, no send)
                  calibrate OUT.jpg         draw every layout.json box over the template
                  check-sftp                connect + log in to T24 SFTP and check the folder (uploads nothing)
                  check-source-sftp         connect + log in to the onboarding SFTP (reads nothing)
                  check-api NID PASSPORT PHONE   show the raw onboarding API responses for a TEST customer and what each pointer picks
                  decrypt-quarantine IN.enc OUT  decrypt a quarantined file for investigation (needs QUARANTINE_KEY)
                  placeholder               write a stand-in template.png from layout.json (dev only)""");
    }
}
