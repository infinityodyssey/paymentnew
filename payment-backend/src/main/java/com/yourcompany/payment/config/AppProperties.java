package com.yourcompany.payment.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/** Typed replacement for the legacy globalConfiguration bean. No magic strings anywhere else. */
@Data
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Security security = new Security();
    private Otp otp = new Otp();
    private Global global = new Global();
    private Lms lms = new Lms();

    @Data
    public static class Security {
        @NotBlank private String tokenHmacSecret;
        @NotBlank private String otpPepper;
        @Min(60) private long sessionTtlSeconds = 900;
        @Min(60) private long paymentTokenTtlSeconds = 900;
        @Min(10) private long nonceWindowSeconds = 60;
        @Min(0) private long clockSkewToleranceSeconds = 5;
        private boolean requireEncryptedPayloads = true;
        private String actuatorUsername = "prometheus";
        private String actuatorPassword;
        private Cors cors = new Cors();

        @Data
        public static class Cors {
            @NotEmpty private List<String> allowedOrigins;
        }
    }

    @Data
    public static class Otp {
        @Min(4) private int length = 5;
        @Min(30) private long ttlSeconds = 180;
        @Min(1) private int maxAttempts = 3;
        @Min(15) private long resendCooldownSeconds = 60;
        @Min(1) private int maxSendsPerAgreementPerHour = 5;
        @Min(60) private long lockoutSeconds = 900;
    }

    /** Endpoints, credentials and constants for the two upstream systems. */
    @Data
    public static class Global {
        @NotBlank private String fiid = "1";

        @NotBlank private String lmsTokenUrl;
        @NotBlank private String lmsFetchUrl;
        private String lmsTokenJsonField = "token";
        @Min(60) private long lmsTokenTtlSeconds = 1500;
        @Min(5) private int lmsTimeoutSeconds = 30;

        @NotBlank private String smsTokenUrl;
        @NotBlank private String smsSendUrl;
        private String smsTokenJsonField = "access_token";
        @Min(60) private long smsTokenTtlSeconds = 1500;
        @Min(5) private int smsTimeoutSeconds = 15;

        private String smsLang = "EN";
        private String smsPurpose = "1001";
        private String smsTemplate;
        private String smsEmpId;
        /** DLT-registered wording. Only {otp} is substituted. */
        private String smsMessageTemplate;

        private String seizedSubStatuses = "";
    }

    @Data
    public static class Lms {
        private String topic = "payment.lms.interaction.v1";
        /** Writes full request and response bodies to the LMS_PAYLOAD logger. Dev only. */
        private boolean logPayloads = false;
    }
}
