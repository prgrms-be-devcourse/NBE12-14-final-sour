package com.back.nbe1214finalsour.domain.chat.repository;

import com.back.nbe1214finalsour.domain.chat.entity.ChatMessage;
import io.awspring.cloud.autoconfigure.core.AwsConnectionDetails;
import io.awspring.cloud.dynamodb.DynamoDbTableNameResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;

/**
 * 에뮬레이터(Floci)에 연결됐을 때만 채팅 메시지 테이블을 만들고 TTL을 켠다 (로컬 docker compose, 통합 테스트).
 *
 * <p>운영에는 에뮬레이터 연결 정보가 없으므로 아무것도 하지 않는다. 운영 테이블은 AWS CLI로 미리 만든다 (계획 D24).
 * 앱이 운영 테이블을 만들지 않으므로 운영 앱에는 읽기·쓰기 권한만 주면 된다. 로고 버킷의 {@code EmulatorBucketInitializer}와 같은 방식이다.
 */
@Component
public class EmulatorChatTableInitializer implements ApplicationRunner {

    private final ObjectProvider<AwsConnectionDetails> emulatorConnection;
    private final DynamoDbClient dynamoDbClient;
    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbTableNameResolver tableNameResolver;

    public EmulatorChatTableInitializer(ObjectProvider<AwsConnectionDetails> emulatorConnection,
                                        DynamoDbClient dynamoDbClient,
                                        DynamoDbEnhancedClient enhancedClient,
                                        DynamoDbTableNameResolver tableNameResolver) {
        this.emulatorConnection = emulatorConnection;
        this.dynamoDbClient = dynamoDbClient;
        this.enhancedClient = enhancedClient;
        this.tableNameResolver = tableNameResolver;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (this.emulatorConnection.getIfAvailable() == null) {
            return;
        }
        String tableName = this.tableNameResolver.resolve(ChatMessage.class);
        if (!tableExists(tableName)) {
            // 키 구성은 ChatMessage의 @DynamoDbPartitionKey·@DynamoDbSortKey에서 가져온다
            this.enhancedClient.table(tableName, TableSchema.fromBean(ChatMessage.class)).createTable();
            this.dynamoDbClient.waiter().waitUntilTableExists(request -> request.tableName(tableName));
        }
        if (!ttlEnabled(tableName)) {
            this.dynamoDbClient.updateTimeToLive(request -> request
                    .tableName(tableName)
                    .timeToLiveSpecification(spec -> spec.attributeName(ChatMessage.TTL_ATTRIBUTE).enabled(true)));
        }
    }

    private boolean tableExists(String tableName) {
        try {
            this.dynamoDbClient.describeTable(request -> request.tableName(tableName));
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    // 이미 켜진 TTL을 다시 켜면 AWS는 ValidationException을 낸다. 컨텍스트가 여러 번 떠도 한 번만 켜도록 먼저 확인한다
    private boolean ttlEnabled(String tableName) {
        TimeToLiveStatus status = this.dynamoDbClient
                .describeTimeToLive(request -> request.tableName(tableName))
                .timeToLiveDescription()
                .timeToLiveStatus();
        return status == TimeToLiveStatus.ENABLED || status == TimeToLiveStatus.ENABLING;
    }
}
