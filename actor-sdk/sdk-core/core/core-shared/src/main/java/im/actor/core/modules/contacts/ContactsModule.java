/*
 * Copyright (C) 2015 Actor LLC. <https://actor.im>
 */

package im.actor.core.modules.contacts;

import java.util.ArrayList;

import im.actor.core.api.base.SeqUpdate;
import im.actor.core.api.rpc.RequestAddContact;
import im.actor.core.api.rpc.RequestRemoveContact;
import im.actor.core.api.rpc.RequestSearchContacts;
import im.actor.core.api.rpc.ResponseSearchContacts;
import im.actor.core.api.rpc.ResponseSeq;
import im.actor.core.api.updates.UpdateContactsAdded;
import im.actor.core.api.updates.UpdateContactsRemoved;
import im.actor.core.entity.PhoneBookContact;
import im.actor.core.entity.User;
import im.actor.core.modules.AbsModule;
import im.actor.core.modules.Modules;
import im.actor.core.viewmodel.Command;
import im.actor.runtime.Storage;
import im.actor.runtime.actors.ActorRef;
import im.actor.runtime.actors.Props;
import im.actor.runtime.storage.ListEngine;
import im.actor.core.entity.Contact;
import im.actor.core.network.RpcCallback;
import im.actor.core.network.RpcException;
import im.actor.core.network.RpcInternalException;
import im.actor.core.viewmodel.UserVM;
import im.actor.runtime.storage.SyncKeyValue;

import static im.actor.runtime.actors.ActorSystem.system;

public class ContactsModule extends AbsModule {

    private ListEngine<Contact> contacts;
    private ListEngine<PhoneBookContact> phoneBook;
    private ActorRef bookImportActor;
    private ActorRef contactSyncActor;
    private SyncKeyValue bookImportState;

    public ContactsModule(final Modules modules) {
        super(modules);

        contacts = Storage.createList(STORAGE_CONTACTS, Contact.CREATOR);
        phoneBook = Storage.createList(STORAGE_PHONE_BOOK, PhoneBookContact.CREATOR);
        bookImportState = new SyncKeyValue(Storage.createKeyValue(STORAGE_BOOK_IMPORT));
    }

    public void run() {
        bookImportActor = system().actorOf(Props.create(() -> new BookImportActor(context())).changeDispatcher("heavy"), "actor/book_import");
        contactSyncActor = system().actorOf(Props.create(() -> new ContactsSyncActor(context())).changeDispatcher("heavy"), "actor/contacts_sync");
    }

    public SyncKeyValue getBookImportState() {
        return bookImportState;
    }

    public ListEngine<Contact> getContacts() {
        return contacts;
    }

    public ListEngine<PhoneBookContact> getPhoneBook() {
        //This one
        return phoneBook;
    }

    public void onPhoneBookChanged() {
        bookImportActor.send(new BookImportActor.PerformSync());
    }

    public ActorRef getContactSyncActor() {
        return contactSyncActor;
    }

    public void markContact(int uid) {
        preferences().putBool("contact_" + uid, true);
    }

    public void markNonContact(int uid) {
        preferences().putBool("contact_" + uid, false);
    }

    public boolean isUserContact(int uid) {
        return preferences().getBool("contact_" + uid, false);
    }

    public Command<UserVM[]> findUsers(final String query) {
        return callback -> request(new RequestSearchContacts(query), new RpcCallback<ResponseSearchContacts>() {
            @Override
            public void onResult(ResponseSearchContacts response) {
                if (response.getUsers().size() == 0) {
                    runOnUiThread(() -> callback.onResult(new UserVM[0]));
                    return;
                }

                updates().onUpdateReceived(new UsersFounded(response.getUsers(), callback));
            }

            @Override
            public void onError(RpcException e) {
                e.printStackTrace();
                runOnUiThread(() -> callback.onResult(new UserVM[0]));
            }
        });
    }

    public Command<Boolean> addContact(final int uid) {
        return callback -> {
            User user = users().getValue(uid);
            if (user == null) {
                runOnUiThread(() -> callback.onError(new RpcInternalException()));
                return;
            }

            request(new RequestAddContact(uid, user.getAccessHash()), new RpcCallback<ResponseSeq>() {
                @Override
                public void onResult(ResponseSeq response) {
                    ArrayList<Integer> uids = new ArrayList<>();
                    uids.add(uid);
                    SeqUpdate update = new SeqUpdate(response.getSeq(), response.getState(),
                            UpdateContactsAdded.HEADER, new UpdateContactsAdded(uids).toByteArray());
                    updates().onUpdateReceived(update);
                    runOnUiThread(() -> callback.onResult(true));
                }

                @Override
                public void onError(RpcException e) {
                    runOnUiThread(() -> callback.onError(new RpcInternalException()));
                }
            });
        };
    }

    public Command<Boolean> removeContact(final int uid) {
        return callback -> {
            User user = users().getValue(uid);
            if (user == null) {
                runOnUiThread(() -> callback.onError(new RpcInternalException()));
                return;
            }

            request(new RequestRemoveContact(uid, user.getAccessHash()), new RpcCallback<ResponseSeq>() {
                @Override
                public void onResult(ResponseSeq response) {
                    ArrayList<Integer> uids = new ArrayList<>();
                    uids.add(uid);
                    SeqUpdate update = new SeqUpdate(response.getSeq(), response.getState(),
                            UpdateContactsRemoved.HEADER, new UpdateContactsRemoved(uids).toByteArray());
                    updates().onUpdateReceived(update);
                    runOnUiThread(() -> callback.onResult(true));
                }

                @Override
                public void onError(RpcException e) {
                    runOnUiThread(() -> callback.onError(new RpcInternalException()));
                }
            });
        };
    }

    public void resetModule() {
        // TODO: Implement
    }
}
