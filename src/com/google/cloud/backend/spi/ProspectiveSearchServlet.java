/*
 * Copyright (c) 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.backend.spi;

import com.google.android.gcm.server.Message;
import com.google.android.gcm.server.Result;
import com.google.android.gcm.server.Sender;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.prospectivesearch.ProspectiveSearchServiceFactory;
import com.google.cloud.backend.config.BackendConfigManager;
import com.google.cloud.backend.pushnotification.Utility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.logging.Logger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;

/**
 * A servlet class for handling Prospective Search API tasks.
 */
@SuppressWarnings("serial")
public class ProspectiveSearchServlet extends HttpServlet {

  private static final int GCM_SEND_RETRIES = 3;

  private static final Logger log = Logger.getLogger(ProspectiveSearchServlet.class.getName());

  private static final BackendConfigManager backendConfigManager = new BackendConfigManager();

  private static final DeviceSubscription deviceSubscription = new DeviceSubscription();

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    // Return if push notification is not enabled
    if (!backendConfigManager.isPushEnabled()) {
      log.info("ProspectiveSearchServlet: couldn't send push notification because it is disabled.");
      return;
    }

    // dispatch GCM messages to each subscribers
    String[] subIds = req.getParameterValues("id");
    // Each subId has this format "<regId>:query:<clientSubId>"
    for (String subId : subIds) {
      String regId = SubscriptionUtility.extractRegId(subId);
      if (isSubscriptionActive(regId)) {
    	Entity matchedEntity = ProspectiveSearchServiceFactory.getProspectiveSearchService().getDocument(req);
    	if(matchedEntity != null) {
    		log.info(String.format("ProspectiveSearchServlet: matchedEntity.toString: " + matchedEntity.toString())); 
    	} else {
            log.info(String.format("ProspectiveSearchServlet: matchedEntity is null.")); 
    	}
        
        sendPushNotification(regId, subId, matchedEntity);
      } else {
        SubscriptionUtility.clearSubscriptionAndDeviceEntity(Arrays.asList(regId));
      }
    }

  }

  private void sendPushNotification(String regId, String subId, Entity matchedEntity) throws IOException {
    SubscriptionUtility.MobileType type = SubscriptionUtility.getMobileType(subId);

    if (SubscriptionUtility.MobileType.ANDROID == type) {
      sendGcmAlert(subId, regId, matchedEntity);
    } else if (SubscriptionUtility.MobileType.IOS == type) {
      sendIosAlert(subId, new String[] {regId}, matchedEntity);
    }
  }

  /**
   * Checks if subscriptions for the device are active.
   *
   * @param deviceId A unique device identifier
   * @return True, if subscriptions are active; False, the otherwise
   */
  private boolean isSubscriptionActive(String deviceId) {
    Date lastDeleteAll = backendConfigManager.getLastSubscriptionDeleteAllTime();
    // If the admin never requested to delete all subscriptions, then this device subscription is
    // still active.
    if (lastDeleteAll == null) {
      return true;
    }

    Entity deviceEntity = deviceSubscription.get(deviceId);
    if (deviceEntity == null) {
      return false;
    }
    Date latestSubscriptionTime = (Date) deviceEntity.getProperty(
        DeviceSubscription.PROPERTY_TIMESTAMP);

    return latestSubscriptionTime.after(lastDeleteAll);
  }

  private void sendGcmAlert(String subId, String regId, Entity matchedEntity)
      throws IOException {
    String gcmKey = backendConfigManager.getGcmKey();
    boolean isGcmKeySet = !(gcmKey == null || gcmKey.trim().length() == 0);

    // Only attempt to send GCM if GcmKey is available
    if (isGcmKeySet) {
      Sender sender = new Sender(gcmKey);
      
      if(matchedEntity != null) {
    	  Message message = new Message.Builder().addData(SubscriptionUtility.GCM_KEY_SUBID, subId)
					.addData("data.message", (String) matchedEntity.getProperty("message"))
					.addData("data.updatedBy", (String) matchedEntity.getProperty("_updatedBy"))
					.addData("data.owner", (String) matchedEntity.getProperty("_owner"))
					.addData("data.sound", "new_message")
					//extra data.<key> elements can be added here
					.build();
    	  
    	  Result r = sender.send(message, regId, GCM_SEND_RETRIES);
          if (r.getMessageId() != null) {
            log.info("ProspectiveSearchServlet: GCM sent: subId: " + subId);
          } else {
            log.warning("ProspectiveSearchServlet: GCM error for subId: " + subId +
                ", senderId: " + gcmKey + ", error: " + r.getErrorCodeName());
            ArrayList<String> deviceIds = new ArrayList<String>();
            deviceIds.add(regId);
            SubscriptionUtility.clearSubscriptionAndDeviceEntity(deviceIds);
          }
      }  
    } else {
      // Otherwise, just write a log entry
      log.info(String.format("ProspectiveSearchServlet: GCM is not sent: GcmKey: %s ", 
          isGcmKeySet));
    }
  }

  private void sendIosAlert(String subId, String[] deviceTokens, Entity matchedEntity) {
	//TODO consider using the matchedEntity.
    log.info("Sending iOS push alert to backend");
    Utility.enqueuePushAlert(subId, deviceTokens);
    log.info("Push alert enqueued successfully");
  }
}
