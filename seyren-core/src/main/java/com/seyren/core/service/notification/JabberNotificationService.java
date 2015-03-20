/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.seyren.core.service.notification;

import static java.lang.String.*;

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

import org.jivesoftware.smack.SmackException;
import org.slf4j.LoggerFactory;

import com.seyren.core.domain.Alert;
import com.seyren.core.domain.AlertType;
import com.seyren.core.domain.Check;
import com.seyren.core.domain.Subscription;
import com.seyren.core.domain.SubscriptionType;
import com.seyren.core.exception.NotificationFailedException;
import com.seyren.core.util.config.SeyrenConfig;

import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jivesoftware.smackx.ping.PingManager;


@Named
public class JabberNotificationService implements NotificationService {
    private static XMPPTCPConnectionConfiguration config;
    private static XMPPTCPConnection connection;
    private static MultiUserChat muc;
    private static MultiUserChatManager manager;
    private static org.slf4j.Logger LOGGER = LoggerFactory.getLogger(JabberNotificationService.class);
    private final SeyrenConfig seyrenConfig;

    @Inject
    public JabberNotificationService(SeyrenConfig seyrenConfig) {
        final String value = System.getenv("JABBER_USER");
        LOGGER.error(value);

        this.seyrenConfig = seyrenConfig;
        PingManager.setDefaultPingInterval(60);
        config = XMPPTCPConnectionConfiguration
                .builder()
                .setHost(seyrenConfig.getJabberHost())
                .setPort(seyrenConfig.getJabberPort())
                .setUsernameAndPassword(seyrenConfig.getJabberUser(), seyrenConfig.getJabberPassword())
                .setServiceName(seyrenConfig.getJabberServiceName())
                .setCompressionEnabled(false)
                .build();
        connection = new XMPPTCPConnection(config);
        try {
            connection.connect();
            connection.login(seyrenConfig.getJabberUser(), seyrenConfig.getJabberPassword());
            manager = MultiUserChatManager.getInstanceFor(connection);
            muc = manager.getMultiUserChat(seyrenConfig.getJabberRoom());
            muc.join(seyrenConfig.getJabberHandle());
        } catch (Exception e) {
            LOGGER.error("Could Not Connect to Jabber");
        }
    }

    @Override
    public void sendNotification(Check check, Subscription subscription, List<Alert> alerts) throws NotificationFailedException {
        List<String> channels = Arrays.asList(subscription.getTarget().split(","));
        try {
            String message = createMessage(check);
            sendMessage(message, subscription);
        } catch (SmackException.NotConnectedException e) {
            throw new NotificationFailedException("Could not send message", e);
        }
    }

    private String createMessage(Check check) {
        String checkUrl = seyrenConfig.getBaseUrl() + "/#/checks/" + check.getId();

        if (check.getState() == AlertType.ERROR) {
            return format("CRITICAL: %s | Please check %s.", check.getName(), checkUrl);
        }
        if (check.getState() == AlertType.WARN) {
            return format("WARNING: %s | Please check %s.", check.getName(), checkUrl);
        }
        if (check.getState() == AlertType.OK) {
            return format("OK: %s | %s", check.getName(), checkUrl);
        }

        LOGGER.info("Unmanaged check state [%s] for check [%s]", check.getState(), check.getName());
        return "";
    }

    private void sendMessage(String message, Subscription subscription) throws SmackException.NotConnectedException {
        // Send Group Message If Room is Defined
        if (seyrenConfig.getJabberRoom() != "") {
            Message groupMessage = new Message(seyrenConfig.getJabberRoom());
            groupMessage.setBody(message);
            groupMessage.setType(Message.Type.groupchat);
            try {
                connection.sendPacket(groupMessage);
            } catch (SmackException.NotConnectedException e) {
                throw new SmackException.NotConnectedException();
            }
        }
        // Send Subscription to Users
        if (subscription.getTarget().contains(seyrenConfig.getJabberServiceName())) {
            for (String user : subscription.getTarget().split(",")) {
                Message directMessage = new Message(user);
                directMessage.setBody(message);
                directMessage.setType(Message.Type.chat);
                connection.sendPacket(directMessage);
            }
        }
    }

    @Override
    public boolean canHandle(SubscriptionType subscriptionType) {
        return subscriptionType == SubscriptionType.JABBER;
    }
}