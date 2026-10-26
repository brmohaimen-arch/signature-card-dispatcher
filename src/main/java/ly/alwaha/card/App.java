package ly.alwaha.card;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * java -jar signature-card-dispatcher.jar [--config FILE] COMMAND
 *
 *   serve                         the endpoint for the onboarding app + the dashboard + delivery every poll.seconds
 *   calibrate OUT.jpg             draw every layout.json box over the template
 *   check-api NID PASSPORT PHONE  show the onboarding API response for a TEST customer and what each field picks
 *   check-sftp                    log in to the T24 SFTP and check the folder (uploads nothing)
 *   check-source-sftp             log in to the onboarding SFTP (reads nothing)
 *   decrypt-quarantine IN OUT     decrypt a quarantined file for investigation
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

        switch (args[i]) {
            case "serve" -> serve(cfg, configFile, secrets);
            case "calibrate" -> {
                CardRenderer renderer = new CardRenderer(cfg.templateDir(), cfg.fontRegular(), cfg.fontBold());
                Path out = Path.of(args[i + 1]);
                Files.write(out, JpegWriter.write(TemplateTools.calibration(renderer.template(), renderer.layout()),
                        cfg.jpegQuality(), renderer.layout().dpi()));
                System.out.println("wrote " + out);
            }
            case "check-api" -> {
                if (args.length < i + 4) {
                    System.out.println("usage: check-api NATIONAL_ID PASSPORT_NUMBER PHONE_NUMBER   (a TEST customer)");
                    System.exit(1);
                }
                CustomerLookup lookup = new CustomerLookup(cfg.api());
                if (!lookup.configured()) {
                    System.out.println("onboarding API is not configured (api.base.url / api.account.path)");
                    System.exit(1);
                }
                System.out.println(cfg.api().local() != null ? "LOCAL test files" : "calling " + cfg.api().baseUrl());
                System.out.println(lookup.probe(new CustomerLookup.Identity(args[i + 1], args[i + 2], args[i + 3])));
            }
            case "check-sftp" -> checkSftp("T24", cfg.t24Sftp(), true);
            case "check-source-sftp" -> checkSftp("onboarding", cfg.source().sftp(), false);
            case "decrypt-quarantine" -> {
                new Quarantine(cfg.quarantine(), cfg.quarantineKey()).decrypt(Path.of(args[i + 1]), Path.of(args[i + 2]));
                System.out.println("decrypted to " + args[i + 2] + " (do NOT open it outside an isolated machine)");
            }
            default -> usage();
        }
    }

    private static void serve(Config cfg, Path configFile, Secrets secrets) throws Exception {
        Service service = new Service(cfg, configFile, secrets);
        String password = System.getenv("DASHBOARD_PASSWORD");
        AdminApi admin = password == null ? null : new AdminApi(service, password);
        ApiServer api = new ApiServer(service, admin);
        api.start();
        String base = "http://" + cfg.server().bind() + ":" + api.port();
        System.out.println("profile:   " + (cfg.testProfile() ? "TEST" : "production"));
        System.out.println("endpoint:  " + base + ApiServer.BASE);
        System.out.println("dashboard: " + (admin == null ? "disabled (set DASHBOARD_PASSWORD to enable)" : base + "/dashboard"));
        while (true) {
            Service.Components c = service.current(); // picks up "Save & apply" from the dashboard
            try {
                c.dispatcher().dispatchOutbox();
            } catch (Exception e) {
                c.audit().log("ERROR", null, "reason=\"" + e + "\"");
            }
            Thread.sleep(c.cfg().pollSeconds() * 1000L);
        }
    }

    private static void checkSftp(String name, Config.SftpEndpoint e, boolean describeFolder) {
        System.out.println("connecting to the " + name + " SFTP " + e.user() + "@" + e.host() + ":" + e.port()
                + " (host key from " + e.knownHosts() + ")");
        try (SftpConnection c = SftpConnection.connect(e)) {
            System.out.println("OK: logged in" + (describeFolder ? "; folder " + c.describeRemoteDir() : "")
                    + " (nothing was read, written or deleted)");
        } catch (Exception ex) {
            System.out.println("FAILED: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("""
                usage: java -jar signature-card-dispatcher.jar [--config FILE] COMMAND
                  serve                          endpoint + dashboard + delivery every poll.seconds
                  calibrate OUT.jpg              draw every layout.json box over the template
                  check-api NID PASSPORT PHONE   show the onboarding API response for a TEST customer
                  check-sftp                     log in to the T24 SFTP and check the folder (uploads nothing)
                  check-source-sftp              log in to the onboarding SFTP (reads nothing)
                  decrypt-quarantine IN.enc OUT  decrypt a quarantined file for investigation""");
    }
}
