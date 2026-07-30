package com.trustguard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

@SpringBootApplication
public class TrustGuardApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(TrustGuardApplication.class);
		application.addListeners(new TlsEnforcementListener());
		application.run(args);
	}

	/**
	 * Rule 2.10: refuses to start when the "production" Spring profile
	 * is active and server.ssl.enabled is not true, unless the explicit
	 * override property is set. Registered on
	 * ApplicationEnvironmentPreparedEvent so the fully-resolved
	 * Environment (profiles, application-production.yml, env var
	 * overrides) is available before any bean is constructed — the
	 * earliest point a genuine "refuse to start" gate can fire.
	 *
	 * "environment = PRODUCTION" is interpreted here as the Spring
	 * "production" profile being active, since that maps directly onto
	 * application-production.yml (this same batch). This is a judgment
	 * call, not an explicit Contract mapping — flagged in the batch
	 * response's assumptions.
	 *
	 * CF-001 (Code v2): the override check now reads
	 * environment.getProperty() rather than System.getenv() directly.
	 * Spring's Environment abstraction already includes OS environment
	 * variables as a property source, so a real deployment setting
	 * TRUSTGUARD_ALLOW_INSECURE_PRODUCTION=true still works exactly as
	 * before — this change only ADDS the ability to supply the
	 * override via test property sources, -D system properties, or
	 * command-line arguments, none of which System.getenv() could ever
	 * see.
	 */
	static class TlsEnforcementListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

		private static final Logger log = LoggerFactory.getLogger(TlsEnforcementListener.class);
		private static final String PRODUCTION_PROFILE = "production";
		private static final String TLS_OVERRIDE_ENV_VAR = "TRUSTGUARD_ALLOW_INSECURE_PRODUCTION";

		@Override
		public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
			ConfigurableEnvironment environment = event.getEnvironment();

			boolean isProduction = environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE));
			boolean tlsEnabled = environment.getProperty("server.ssl.enabled", Boolean.class, false);

			if (!isProduction || tlsEnabled) {
				return;
			}

			boolean overrideSet = "true".equalsIgnoreCase(environment.getProperty(TLS_OVERRIDE_ENV_VAR));

			if (overrideSet) {
				log.error("SECURITY VIOLATION: TrustGuard is running in PRODUCTION without TLS. "
						+ "API keys, behavioral data, and trust decisions are exposed in transit. "
						+ "This configuration is not supported for production use.");
				return;
			}

			throw new IllegalStateException(
					"Refusing to start: the production profile is active and server.ssl.enabled "
							+ "is not true. Set " + TLS_OVERRIDE_ENV_VAR + "=true to override "
							+ "(Rule 2.10) — not recommended for real production traffic.");
		}
	}
}