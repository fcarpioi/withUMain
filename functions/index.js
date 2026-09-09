const admin = require("firebase-admin");
const {onDocumentCreated} = require("firebase-functions/v2/firestore");

admin.initializeApp();

exports.sendParentNotification = onDocumentCreated(
  "users/{userId}/notifications/{notificationId}",
  async (event) => {
    const notification = event.data && event.data.data();
    if (!notification) return;

    const {userId, notificationId} = event.params;
    const tokensSnapshot = await admin
      .firestore()
      .collection("users")
      .doc(userId)
      .collection("tokens")
      .get();

    const tokens = tokensSnapshot.docs
      .map((doc) => doc.get("token"))
      .filter((token) => typeof token === "string" && token.length > 0);

    const notificationRef = admin
      .firestore()
      .collection("users")
      .doc(userId)
      .collection("notifications")
      .doc(notificationId);

    if (tokens.length === 0) {
      await notificationRef.set({
        pushStatus: "no_tokens",
        pushAttemptedAt: admin.firestore.FieldValue.serverTimestamp()
      }, {merge: true});
      return;
    }

    const response = await admin.messaging().sendEachForMulticast({
      tokens,
      notification: {
        title: notification.title || "WithU Familiar",
        body: notification.message || "New family safety alert"
      },
      data: {
        userId,
        notificationId,
        eventType: notification.eventType || "alert",
        severity: notification.severity || "normal"
      },
      android: {
        priority: "high"
      },
      apns: {
        payload: {
          aps: {
            sound: "default"
          }
        }
      }
    });

    await notificationRef.set({
      pushStatus: response.failureCount > 0 ? "partial_failure" : "sent",
      pushSuccessCount: response.successCount,
      pushFailureCount: response.failureCount,
      pushAttemptedAt: admin.firestore.FieldValue.serverTimestamp()
    }, {merge: true});
  }
);
