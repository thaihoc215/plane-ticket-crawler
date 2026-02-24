package com.planecrawler.service;

import com.planecrawler.model.FlightInfo;
import com.planecrawler.model.PriceAlert;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Sends HTML price-alert emails to users when a flight drops to or below
 * their target price.
 */
@Service
@RequiredArgsConstructor
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    /**
     * Sends a price-alert email if the scraped price meets the target.
     *
     * @param alert  the {@link PriceAlert} that triggered the notification
     * @param flight the scraped {@link FlightInfo} with current price details
     */
    public void sendPriceAlert(PriceAlert alert, FlightInfo flight) {
        Context ctx = new Context();
        ctx.setVariable("userEmail", alert.getUserEmail());
        ctx.setVariable("origin", alert.getOrigin());
        ctx.setVariable("destination", alert.getDestination());
        ctx.setVariable("targetPrice", alert.getTargetPrice());
        ctx.setVariable("currentPrice", flight.getPrice());
        ctx.setVariable("airline", flight.getAirline());
        ctx.setVariable("duration", flight.getDuration());

        String htmlBody = templateEngine.process("price-alert-email", ctx);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(alert.getUserEmail());
            helper.setSubject(String.format("✈ Price Alert: %s → %s is now $%s!",
                    alert.getOrigin(), alert.getDestination(), flight.getPrice()));
            helper.setText(htmlBody, true);

            mailSender.send(message);
            log.info("Price-alert email sent to {} for route {}->{} at price {}",
                    alert.getUserEmail(), alert.getOrigin(), alert.getDestination(), flight.getPrice());
        } catch (MessagingException e) {
            log.error("Failed to send email to {} for alert id={}: {}",
                    alert.getUserEmail(), alert.getId(), e.getMessage(), e);
        }
    }
}
