package im.actor.core.modules.messaging;

import im.actor.core.api.ApiEncryptedContent;
import im.actor.core.api.ApiEncryptedDeleteAll;
import im.actor.core.api.ApiEncryptedDeleteContent;
import im.actor.core.api.ApiEncryptedEditContent;
import im.actor.core.api.ApiEncryptedMessageContent;
import im.actor.core.api.ApiEncryptedRead;
import im.actor.core.api.ApiEncryptedReceived;
import im.actor.core.modules.AbsModule;
import im.actor.core.modules.ModuleContext;
import im.actor.core.modules.encryption.updates.EncryptedSequenceProcessor;
import im.actor.runtime.actors.messages.Void;
import im.actor.runtime.promise.Promise;

public class MessagesProcessorEncrypted extends AbsModule implements EncryptedSequenceProcessor {

    public MessagesProcessorEncrypted(ModuleContext context) {
        super(context);
    }

    @Override
    public Promise<Void> onUpdate(int senderId, long date, ApiEncryptedContent update) {

        if (update instanceof ApiEncryptedMessageContent ||
                update instanceof ApiEncryptedReceived ||
                update instanceof ApiEncryptedRead ||
                update instanceof ApiEncryptedDeleteContent ||
                update instanceof ApiEncryptedEditContent ||
                update instanceof ApiEncryptedDeleteAll) {
            return context().getMessagesModule().getRouter()
                    .onEncryptedUpdate(senderId, date, update);
        }
        return null;
    }
}