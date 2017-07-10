package org.openbase.bco.authentication.core;

/*-
 * #%L
 * BCO Authentication Core
 * %%
 * Copyright (C) 2017 openbase.org
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openbase.bco.authentication.lib.jp.JPCredentialsDirectory;
import org.openbase.bco.authentication.lib.jp.JPInitializeCredentials;
import org.openbase.bco.authentication.lib.jp.JPRegistrationMode;
import org.openbase.jps.core.JPService;
import org.openbase.jps.exception.JPNotAvailableException;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.InitializationException;
import org.openbase.jul.exception.NotAvailableException;
import org.openbase.jul.exception.printer.ExceptionPrinter;
import org.openbase.jul.exception.printer.LogLevel;
import org.openbase.jul.processing.JSonObjectFileProcessor;
import org.slf4j.LoggerFactory;
import rst.domotic.authentication.LoginCredentialsType.LoginCredentials;

/**
 * This class provides access to the storage of login credentials.
 *
 * @author <a href="mailto:cromankiewicz@techfak.uni-bielefeld.de">Constantin Romankiewicz</a>
 */
public class AuthenticationRegistry {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(AuthenticationRegistry.class);

    private static final String FILENAME = "credentials.dat";

    protected HashMap<String, LoginCredentials> credentials;

    private final JSonObjectFileProcessor<HashMap> fileProcessor;
    private File file;

    private final long startingTime;

    public AuthenticationRegistry() {
        this.fileProcessor = new JSonObjectFileProcessor<>(HashMap.class);
        this.startingTime = System.currentTimeMillis();
    }

    public void init() throws InitializationException {
        try {
            this.file = new File(JPService.getProperty(JPCredentialsDirectory.class).getValue(), FILENAME);
            this.load();
            this.setPermissions();
        } catch (JPNotAvailableException | CouldNotPerformException | IOException ex) {
            throw new InitializationException(AuthenticationRegistry.class, ex);
        }
    }

    /**
     * Get the encrypted login credentials for a given user.
     *
     * @param userId ID of the user whose credentials should be retrieved.
     * @return The encrypted credentials, if they could be found.
     * @throws NotAvailableException If the user does not exist in the credentials storage.
     */
    public byte[] getCredentials(String userId) throws NotAvailableException {
        if (!credentials.containsKey(userId)) {
            throw new NotAvailableException(userId);
        }

        return credentials.get(userId).getCredentials().toByteArray();
    }

    /**
     * Tells whether a given user has administrator permissions.
     *
     * @param userId ID of the user whose credentials should be retrieved.
     * @return Boolean value indicating whether the user has administrator permissions.
     * @throws NotAvailableException If the user does not exist in the credentials storage.
     */
    public boolean isAdmin(String userId) throws NotAvailableException {
        if (!credentials.containsKey(userId)) {
            throw new NotAvailableException(userId);
        }

        return credentials.get(userId).getAdmin();
    }
    
    /**
     * Changes the admin-flag of an entry.
     * 
     * @param userId user to change flag of
     * @param isAdmin boolean whether user is admin or not
     * @throws NotAvailableException Throws if there is no user given userId
     */
    public void setAdmin(String userId, boolean isAdmin) throws NotAvailableException {
        if (!credentials.containsKey(userId))
            throw new NotAvailableException(userId);
        LoginCredentials loginCredentials = LoginCredentials.newBuilder(this.credentials.get(userId))
            .setAdmin(isAdmin)
            .build();
        this.credentials.put(userId, loginCredentials);
        this.save();
    }

    /**
     * Sets the login credentials for a given user. If there is already an entry in the storage for
     * this user, it will be replaced. Otherwise, a new entry without admin rights will be created.
     *
     * @param userId ID of the user to modify.
     * @param credentials New encrypted credentials.
     * @throws org.openbase.jul.exception.CouldNotPerformException
     */
    public void setCredentials(String userId, byte[] credentials) throws CouldNotPerformException {
        if (!this.credentials.containsKey(userId)) {
            this.setCredentials(userId, credentials, false);
        } else {
            this.setCredentials(userId, credentials, this.credentials.get(userId).getAdmin());
        }
    }

    public void setCredentials(String userId, byte[] credentials, boolean admin) throws CouldNotPerformException {
        // only test for registration mode if user is not already registered
        try {
            int registrationMode = JPService.getProperty(JPRegistrationMode.class).getValue();
            if (registrationMode == JPRegistrationMode.DEFAULT_REGISTRATION_MODE) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable(new CouldNotPerformException("Could not set credentials for user[" + userId + "]. Registration mode not active."), LOGGER, LogLevel.WARN);
            } else if (TimeUnit.MILLISECONDS.convert(registrationMode, TimeUnit.MINUTES) < (System.currentTimeMillis() - this.startingTime)) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable(new CouldNotPerformException("Could not set credentials for user[" + userId + "]. Registration mode already expired."), LOGGER, LogLevel.WARN);
            }
        } catch (JPNotAvailableException ex) {
            throw new CouldNotPerformException("Could not access JPRegistrationMode proptery.", ex);
        }

        LoginCredentials loginCredentials = LoginCredentials.newBuilder()
                .setId(userId)
                .setCredentials(ByteString.copyFrom(credentials))
                .setAdmin(admin)
                .build();

        this.credentials.put(userId, loginCredentials);
        this.save();
    }
    
    /**
     * Determines if there is an entry with given id.
     * 
     * @param id the id to check
     * @return true if existent, false otherwise
     */
    public boolean hasEntry(String id) {
        return credentials.containsKey(id);
    }
    
    /**
     * Removes entry from store given id.
     * 
     * @param id the credentials to remove
     */
    public void removeEntry(String id) {
        if (this.hasEntry(id)) this.credentials.remove(id);
        this.save();
    }

    /**
     * Loads the credentials from a protobuf binary file.
     *
     * @throws CouldNotPerformException If the deserialization fails.
     */
    protected void load() throws CouldNotPerformException {
        try {
            if (!file.exists() && JPService.getProperty(JPInitializeCredentials.class).getValue()) {
                credentials = new HashMap<>();
                save();
            }
        } catch (JPNotAvailableException ex) {
            throw new CouldNotPerformException("Initialize credential property not available!", ex);
        }

        credentials = new HashMap<>();
        try {
            final CodedInputStream inputStream = CodedInputStream.newInstance(new FileInputStream(file));

            while (!inputStream.isAtEnd()) {
                LoginCredentials entry = LoginCredentials.parseFrom(inputStream);
                credentials.put(entry.getId(), entry);
            }
        } catch (IOException ex) {
            Logger.getLogger(AuthenticationRegistry.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Stores the credentials in a protobuf binary file.
     */
    protected void save() {
        try {
            final CodedOutputStream outputStream = CodedOutputStream.newInstance(new FileOutputStream(file));

            for (LoginCredentials entry : credentials.values()) {
                entry.writeTo(outputStream);
            }

            outputStream.flush();
        } catch (IOException ex) {
            ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
        }
    }

    /**
     * Sets the permissions to UNIX 600.
     *
     * @throws IOException
     */
    private void setPermissions() throws IOException {
        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);

        Files.setPosixFilePermissions(file.toPath(), perms);
    }
}
