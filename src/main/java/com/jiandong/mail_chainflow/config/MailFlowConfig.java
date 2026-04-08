package com.jiandong.mail_chainflow.config;

import java.io.IOException;
import java.util.function.Function;

import com.jiandong.mail_chainflow.agent.AgentContext;
import com.jiandong.mail_chainflow.agent.MailAgents;
import com.jiandong.mail_chainflow.parser.MimeMessageParser;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mail.dsl.Mail;
import org.springframework.mail.MailMessage;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.messaging.Message;

@Configuration
@EnableConfigurationProperties(MailFlowConfig.ImapMailProperties.class)
public class MailFlowConfig {

	private static final Logger log = LoggerFactory.getLogger(MailFlowConfig.class);

	@Value("${spring.mail.username}")
	private String from;

	@ConfigurationProperties("inbound-mail")
	public record ImapMailProperties(String host, Integer port, String username, String password, String protocol) {

	}

	@Bean
	public IntegrationFlow imapMailFlow(ImapMailProperties mailProperties, MailAgents mailAgents, JavaMailSender javaMailSender) {
		var user = mailProperties.username.replace("@", "%40");
		var url = "%s://%s:%s@%s:%d/INBOX".formatted(mailProperties.protocol, user, mailProperties.password, mailProperties.host, mailProperties.port);
		return IntegrationFlow.from(Mail.imapInboundAdapter(url)
								.shouldDeleteMessages(false)
								.shouldMarkMessagesAsRead(true)
								.autoCloseFolder(false),
						e -> e
								.autoStartup(true)
								.poller(p -> p
										.fixedDelay(20 * 1000)
										.errorChannel("mailErrorChannel")
								)
				)
				.log((Function<Message<MimeMessage>, Object>) mimeMessageMessage -> "Received mail message: " + mimeMessageMessage.getPayload())
				.transform(this::convertToAgentContext)
				.log((Function<Message<AgentContext>, Object>) agentCtx -> "POST Transform: " + agentCtx.getPayload().subject)
				.transform(mailAgents::classify)
				.log((Function<Message<AgentContext>, Object>) agentCtx -> "category: " + agentCtx.getPayload().category)
				.filter(AgentContext.class, agentCtx -> !"IGNORED".equals(agentCtx.category))
				.transform(mailAgents::resolve)
				.log((Function<Message<AgentContext>, Object>) agentCtx -> "resolutionAction: " + agentCtx.getPayload().resolutionAction + ", policyReasoning: " + agentCtx.getPayload().policyReasoning)
				.transform(mailAgents::draftReply)
				.transform(AgentContext.class, this::convertToMailMessage)
				.handle(Mail.outboundAdapter(javaMailSender))
				.get();
	}

	@Bean
	public IntegrationFlow mailErrorFlow() {
		return IntegrationFlow.from("mailErrorChannel")
				.handle(message -> {
					Throwable cause = (Throwable) message.getPayload();
					log.error("Error processing email", cause);
				})
				.get();
	}

	private MailMessage convertToMailMessage(AgentContext context) {
		SimpleMailMessage mailMessage = new SimpleMailMessage();
		mailMessage.setFrom(from);
		mailMessage.setTo(context.sender);
		mailMessage.setSubject("Re: " + context.subject);
		mailMessage.setText(context.draftedReplyBody);
		return mailMessage;
	}

	private AgentContext convertToAgentContext(MimeMessage payload) {
		AgentContext agentContext = new AgentContext();
		try {

			MimeMessageParser parser = new MimeMessageParser(payload);
			parser.parse();
			agentContext.sender = parser.getFrom();
			agentContext.subject = parser.getSubject();
			agentContext.originalEmailBody = parser.getPlainContent();
		}
		catch (MessagingException | IOException e) {
			throw new RuntimeException("Failed to parse email message", e);
		}
		return agentContext;
	}

}
