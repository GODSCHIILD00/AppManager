/*
 * Copyright (C) 2020 Muntashir Al-Islam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package android.os;

import android.content.pm.UserInfo;

import java.util.List;

import androidx.annotation.RequiresApi;

public interface IUserManager extends IInterface {
    UserInfo getPrimaryUser() throws RemoteException;

    List<UserInfo> getUsers(boolean excludeDying) throws RemoteException;

    // Changed in 10.0.0_r30
    @RequiresApi(api = Build.VERSION_CODES.Q)
    List<UserInfo> getUsers(boolean excludePartial, boolean excludeDying, boolean excludePreCreated)
            throws RemoteException;

    int getManagedProfileBadge(int userId) throws RemoteException;

    abstract class Stub {
        public static IUserManager asInterface(android.os.IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
