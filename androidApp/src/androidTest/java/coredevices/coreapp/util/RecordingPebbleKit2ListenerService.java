package coredevices.coreapp.util;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

// The PebbleKit 2 listener service a companion app would export, declared by the test APK
// under the library's RECEIVE_DATA_FROM_WATCH action. It reports every request's ACTION as a
// broadcast and answers each request with an empty bundle, which the connector reads as
// success, so a session test can tell whether the app bound out and what it sent.
//
// A bound service of the test package runs in the test package's own process, which holds
// only the test APK's classes: no pebblekit2 AIDL stubs, no Kotlin runtime, and no static
// shared with the test. So this is Java against the Android classes alone, the binder speaks
// the generated AIDL protocol directly (interface descriptor, transaction code 1 for the one
// method of each interface, a Bundle as a presence int plus the parcelable, a no-exception
// reply), and the actions travel back to the test as ACTION_RECORDED broadcasts.
//
// Plain comments throughout: lint running on a JDK 17 host fails while parsing a Javadoc
// comment in this source set (its Javadoc parser needs a Java 21 List method).
public final class RecordingPebbleKit2ListenerService extends Service {

    private static final String REQUEST_RESPONSE = "io.rebble.pebblekit2.common.UniversalRequestResponse";
    private static final String SEND_DATA_CALLBACK = "io.rebble.pebblekit2.common.SendDataCallback";
    private static final int TRANSACTION_REQUEST = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_ON_RESULT = IBinder.FIRST_CALL_TRANSACTION;

    // Broadcast once per request the service received, carrying EXTRA_ACTION.
    public static final String ACTION_RECORDED = "coredevices.coreapp.test.PEBBLEKIT2_LISTENER_REQUEST";
    public static final String EXTRA_ACTION = "action";

    @Override
    public IBinder onBind(Intent intent) {
        return new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code != TRANSACTION_REQUEST) {
                    return super.onTransact(code, data, reply, flags);
                }
                data.enforceInterface(REQUEST_RESPONSE);
                Bundle request = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : new Bundle();
                IBinder callback = data.readStrongBinder();
                String action = request.getString("ACTION");
                sendBroadcast(new Intent(ACTION_RECORDED).putExtra(EXTRA_ACTION, action == null ? "" : action));
                if (reply != null) {
                    reply.writeNoException();
                }
                Parcel call = Parcel.obtain();
                Parcel result = Parcel.obtain();
                try {
                    call.writeInterfaceToken(SEND_DATA_CALLBACK);
                    call.writeInt(1);
                    new Bundle().writeToParcel(call, 0);
                    callback.transact(TRANSACTION_ON_RESULT, call, result, 0);
                    result.readException();
                } finally {
                    call.recycle();
                    result.recycle();
                }
                return true;
            }
        };
    }
}
