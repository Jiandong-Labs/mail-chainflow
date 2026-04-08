package com.jiandong.mail_chainflow.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MailAgents {

	private final String classifyPrompt;

	private final String resolvePrompt;

	private final String draftPrompt;

	private final ChatClient chatClient;

	public MailAgents(ChatClient.Builder chatClientBuilder,
			@Value("${mail-chainflow.classify.prompt}") String classifyPrompt,
			@Value("${mail-chainflow.resolve.prompt}") String resolvePrompt,
			@Value("${mail-chainflow.draft.prompt}") String draftPrompt) {
		this.chatClient = chatClientBuilder.build();
		this.classifyPrompt = classifyPrompt;
		this.resolvePrompt = resolvePrompt;
		this.draftPrompt = draftPrompt;
	}

	public AgentContext classify(AgentContext context) {
		ClassifierResult result = this.chatClient.prompt()
				.user(String.format(classifyPrompt, context.originalEmailBody))
				.call()
				.entity(new BeanOutputConverter<>(ClassifierResult.class));

		if (result != null) {
			context.category = result.isSupportTicket() ? result.category() : "TECHNICAL";
		}
		else {
			context.category = "TECHNICAL";
		}
		return context;
	}

	public AgentContext resolve(AgentContext context) {
		ResolverResult result = this.chatClient.prompt()
				.user(String.format(this.resolvePrompt, context.category, context.originalEmailBody))
				.call()
				.entity(new BeanOutputConverter<>(ResolverResult.class));

		if (result != null) {
			context.resolutionAction = result.action();
			context.policyReasoning = result.reasoning();
		}
		return context;
	}

	public AgentContext draftReply(AgentContext context) {
		context.draftedReplyBody = this.chatClient.prompt()
				.user(String.format(this.draftPrompt, context.resolutionAction, context.originalEmailBody))
				.call()
				.content();

		return context;
	}

}
