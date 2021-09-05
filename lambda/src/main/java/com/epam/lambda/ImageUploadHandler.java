package com.epam.lambda;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.GetQueueUrlRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;

public class ImageUploadHandler implements RequestHandler<Map<String, Object>, String> {

    private static final String SQS_QUEUE_NAME = "image-nitification-queue";
    private static final String TOPIC_ARN = "arn:aws:sns:eu-central-1:394791350075:image-notification-topic";
    private static final String REGION = "eu-central-1";
    private static final String API = "API";
    private static final String DETAIL_TYPE = "detail-type";
    private static final Integer RECEIVE_MESSAGE_TIMEOUT = 15;
    private static final Integer MAX_NUMBER_OF_MESSAGES = 3;

    private AmazonSNS amazonSNS = AmazonSNSClientBuilder.standard().withRegion(REGION).build();
    private AmazonSQS amazonSQS = AmazonSQSClientBuilder.standard().withRegion(REGION).build();

    private LambdaLogger logger;

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        Object detail = input.get(DETAIL_TYPE);
        String detailType = detail == null ? API : String.valueOf(detail);
        logger = context.getLogger();

        int processedMessages = processQueueMessages();
        logger.log("Handled request for ARN: " + TOPIC_ARN
                + "; Request Source = " + detailType
                + "; Function Name = " + context.getFunctionName()
                + "; Processed message Count = " + processedMessages
                + "; Remaining Time in millis = " + context.getRemainingTimeInMillis());

        return "ImageUploadHandler: handleRequest with status 200 OK";
    }

    private int processQueueMessages() {

        GetQueueUrlRequest getQueueUrlRequest = new GetQueueUrlRequest().withQueueName(SQS_QUEUE_NAME);
        String queueUrl = amazonSQS.getQueueUrl(getQueueUrlRequest).getQueueUrl();

        ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest()
                .withQueueUrl(queueUrl)
                .withMaxNumberOfMessages(MAX_NUMBER_OF_MESSAGES)
                .withWaitTimeSeconds(RECEIVE_MESSAGE_TIMEOUT);

        List<Message> sqsMessages = IntStream.of(0, MAX_NUMBER_OF_MESSAGES)
                .mapToObj(i -> amazonSQS.receiveMessage(receiveMessageRequest).getMessages())
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        if (sqsMessages.isEmpty()) {
            logger.log("Messages not found. End processing");
            return 0;
        }

        String message = sqsMessages.stream()
                .map(Message::getBody)
                .collect(Collectors.joining("\n==============================\n"));
        logger.log("Result message = \n" + message);

        PublishRequest publishRequest = new PublishRequest()
                .withTopicArn(TOPIC_ARN)
                .withSubject("Proceesed SQS Queue Messages")
                .withMessage(message);

        amazonSNS.publish(publishRequest);

        sqsMessages.forEach(sqsMessage -> {
            logger.log("Delete message id = " + sqsMessage.getMessageId());
            amazonSQS.deleteMessage(queueUrl, sqsMessage.getReceiptHandle());
        });
        return  sqsMessages.size();
    }
}
