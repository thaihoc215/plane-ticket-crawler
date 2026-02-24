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

import java.math.BigDecimal;
import java.util.List;

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
     * Sends a price-alert email with all flights that meet or beat the target price.
     *
     * @param alert            the {@link PriceAlert} that triggered the notification
     * @param matchedOutbound  outbound flights at or below the target price
     * @param matchedReturn    return flights at or below the return target price (empty if one-way)
     * @param roundTripMatched whether the round-trip price met the target
     * @param roundTripPrice   the cheapest round-trip price from the airline (null if not applicable)
     * @param matchedRoundTrip round-trip flights at or below the round-trip target price (empty if not applicable)
     */
    public void sendPriceAlert(PriceAlert alert, List<FlightInfo> matchedOutbound, List<FlightInfo> matchedReturn,
                                boolean roundTripMatched, BigDecimal roundTripPrice,
                                List<FlightInfo> matchedRoundTrip) {
        Context ctx = new Context();
        ctx.setVariable("userEmail", alert.getUserEmail());
        ctx.setVariable("origin", alert.getOrigin());
        ctx.setVariable("destination", alert.getDestination());
        ctx.setVariable("tripType", alert.getTripType());
        ctx.setVariable("departureDate", alert.getDepartureDate());
        ctx.setVariable("returnDate", alert.getReturnDate());
        ctx.setVariable("targetPrice", alert.getTargetPrice());
        ctx.setVariable("returnTargetPrice", alert.getReturnTargetPrice());
        ctx.setVariable("roundTripTargetPrice", alert.getRoundTripTargetPrice());
        ctx.setVariable("roundTripMatched", roundTripMatched);
        ctx.setVariable("roundTripPrice", roundTripPrice);
        ctx.setVariable("outboundFlights", matchedOutbound);
        ctx.setVariable("returnFlights", matchedReturn);
        ctx.setVariable("roundTripFlights", matchedRoundTrip);

        String htmlBody = templateEngine.process("price-alert-email", ctx);

        String cheapestOutbound = matchedOutbound.isEmpty() ? "-" : "$" + matchedOutbound.get(0).price();
        String cheapestReturn = matchedReturn.isEmpty() ? "" : ", return " + "$" + matchedReturn.get(0).price();

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(alert.getUserEmail());
            helper.setSubject(String.format("✈ Price Alert: %s → %s (%s%s) – %d flight(s) found",
                    alert.getOrigin(), alert.getDestination(), cheapestOutbound, cheapestReturn,
                    matchedOutbound.size() + matchedReturn.size()));
            helper.setText(htmlBody, true);

            mailSender.send(message);
            log.info("Price-alert email sent to {} for route {}->{} with {} outbound and {} return matches",
                    alert.getUserEmail(), alert.getOrigin(), alert.getDestination(),
                    matchedOutbound.size(), matchedReturn.size());
        } catch (MessagingException e) {
            log.error("Failed to send email to {} for alert id={}: {}",
                    alert.getUserEmail(), alert.getId(), e.getMessage(), e);
        }
    }
}
