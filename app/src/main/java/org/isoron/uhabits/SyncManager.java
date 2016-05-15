/*
 * Copyright (C) 2016 Álinson Santos Xavier <isoron@gmail.com>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits;

import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.support.v7.preference.PreferenceManager;
import android.util.Log;

import org.isoron.uhabits.commands.Command;
import org.isoron.uhabits.commands.CommandParser;
import org.isoron.uhabits.helpers.DatabaseHelper;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.LinkedList;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class SyncManager
{
    public static final String EXECUTE_COMMAND = "executeCommand";
    public static final String POST_COMMAND = "postCommand";
    public static final String SYNC_SERVER_URL = "http://sync.loophabits.org:4000";

    private static String GROUP_KEY;
    private static String CLIENT_ID;
    private final SharedPreferences prefs;

    @NonNull
    private Socket socket;
    private BaseActivity activity;
    private LinkedList<Command> outbox;

    public SyncManager(BaseActivity activity)
    {
        this.activity = activity;
        outbox = new LinkedList<>();

        prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        GROUP_KEY = prefs.getString("syncKey", DatabaseHelper.getRandomId());
        CLIENT_ID = DatabaseHelper.getRandomId();

        try
        {
            socket = IO.socket(SYNC_SERVER_URL);
            socket.connect();
            socket.on(Socket.EVENT_CONNECT, new OnConnectListener());
            socket.on(EXECUTE_COMMAND, new OnExecuteCommandListener());
        }
        catch (URISyntaxException e)
        {
            throw new RuntimeException(e.getMessage());
        }
    }

    public void postCommand(Command command)
    {
        JSONObject msg = command.toJSON();
        if(msg != null)
        {
            socket.emit(POST_COMMAND, msg.toString());
            outbox.add(command);
        }
    }

    public void close()
    {
        socket.off();
        socket.close();
    }

    private class OnConnectListener implements Emitter.Listener
    {
        @Override
        public void call(Object... args)
        {
            JSONObject authMsg = buildAuthMessage();
            socket.emit("auth", authMsg.toString());

            Long lastSync = prefs.getLong("lastSync", 0);
            JSONObject fetchMsg = buildFetchMessage(lastSync);
            socket.emit("fetchCommands", fetchMsg.toString());
        }

        private JSONObject buildFetchMessage(Long lastSync)
        {
            try
            {
                JSONObject json = new JSONObject();
                json.put("since", lastSync);
                return json;
            }
            catch (JSONException e)
            {
                throw new RuntimeException(e.getMessage());
            }
        }

        private JSONObject buildAuthMessage()
        {
            try
            {
                JSONObject json = new JSONObject();
                json.put("groupKey", GROUP_KEY);
                json.put("clientId", CLIENT_ID);
                json.put("version", BuildConfig.VERSION_NAME);
                return json;
            }
            catch (JSONException e)
            {
                throw new RuntimeException(e.getMessage());
            }
        }
    }

    private class OnExecuteCommandListener implements Emitter.Listener
    {
        @Override
        public void call(Object... args)
        {
            try
            {
                JSONObject root = new JSONObject(args[0].toString());
                updateLastSync(root.getLong("timestamp"));
                executeCommand(args[0]);
            }
            catch (JSONException e)
            {
                throw new RuntimeException(e.getMessage());
            }
        }
    }

    private void executeCommand(Object arg) throws JSONException
    {
        Log.d("SyncManager", String.format("Received command: %s", arg.toString()));
        JSONObject root = new JSONObject(arg.toString());

        Command received = CommandParser.fromJSON(root);
        if(received == null) throw new RuntimeException("received is null");

        for(Command pending : outbox)
        {
            if(pending.getId().equals(received.getId()))
            {
                outbox.remove(pending);
                Log.d("SyncManager", "Received command discarded");
                return;
            }
        }

        activity.executeCommand(received, null, false);
        Log.d("SyncManager", "Received command executed");
    }

    private void updateLastSync(Long timestamp)
    {
        prefs.edit().putLong("lastSync", timestamp).apply();
    }
}
