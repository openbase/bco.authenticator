package org.openbase.bco.authentication.lib;

/*-
 * #%L
 * BCO Authentication Library
 * %%
 * Copyright (C) 2017 openbase.org
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.FatalImplementationErrorException;
import org.openbase.jul.exception.NotAvailableException;
import org.openbase.jul.exception.PermissionDeniedException;
import org.openbase.jul.exception.RejectedException;
import org.openbase.jul.exception.printer.ExceptionPrinter;
import org.openbase.jul.exception.printer.LogLevel;
import org.slf4j.LoggerFactory;
import rst.domotic.authentication.LoginCredentialsType;
import rst.domotic.authentication.TicketAuthenticatorWrapperType.TicketAuthenticatorWrapper;
import rst.domotic.authentication.TicketSessionKeyWrapperType;

/**
 *
 * @author <a href="mailto:sfast@techfak.uni-bielefeld.de">Sebastian Fast</a>
 */
public class SessionManager {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(SessionManager.class);

    private TicketAuthenticatorWrapper ticketAuthenticatorWrapper;
    private byte[] sessionKey;

    public SessionManager() {
    }

    public TicketAuthenticatorWrapper getTicketAuthenticatorWrapper() {
        return ticketAuthenticatorWrapper;
    }

    public void setTicketAuthenticatorWrapper(TicketAuthenticatorWrapper ticketAuthenticatorWrapper) {
        this.ticketAuthenticatorWrapper = ticketAuthenticatorWrapper;
    }

    public byte[] getSessionKey() {
        return sessionKey;
    }

    public void initializeServiceServerRequest() throws RejectedException {
        try {
            this.ticketAuthenticatorWrapper = AuthenticationClientHandler.initServiceServerRequest(this.getSessionKey(), this.getTicketAuthenticatorWrapper());
        } catch (IOException ex) {
            throw new RejectedException("Initializing request rejected", ex);
        }
    }

    /**
     * Wraps the whole login process into one method
     *
     * @param clientId Identifier of the client - must be present in client
     * database
     * @param clientPassword Password of the client
     * @return Returns Returns an TicketAuthenticatorWrapperWrapper containing
     * both the ClientServerTicket and Authenticator
     * @throws StreamCorruptedException If the password was wrong.
     * @throws NotAvailableException If the entered clientId could not be found.
     * @throws CouldNotPerformException In case of a communication error between client and server.
     */
    public boolean login(String clientId, String clientPassword) throws StreamCorruptedException, CouldNotPerformException, NotAvailableException, RejectedException {
        try {
            // init KDC request on client side
            byte[] clientPasswordHash = EncryptionHelper.hash(clientPassword);

            // request TGT
            TicketSessionKeyWrapperType.TicketSessionKeyWrapper ticketSessionKeyWrapper = CachedAuthenticationRemote.getRemote().requestTicketGrantingTicket(clientId).get();

            // handle KDC response on client side
            List<Object> list = AuthenticationClientHandler.handleKeyDistributionCenterResponse(clientId, clientPasswordHash, ticketSessionKeyWrapper);
            TicketAuthenticatorWrapper taw = (TicketAuthenticatorWrapper) list.get(0); // save at somewhere temporarily
            byte[] ticketGrantingServiceSessionKey = (byte[]) list.get(1); // save TGS session key somewhere on client side

            // request CST
            ticketSessionKeyWrapper = CachedAuthenticationRemote.getRemote().requestClientServerTicket(taw).get();

            // handle TGS response on client side
            list = AuthenticationClientHandler.handleTicketGrantingServiceResponse(clientId, ticketGrantingServiceSessionKey, ticketSessionKeyWrapper);
            this.ticketAuthenticatorWrapper = (TicketAuthenticatorWrapper) list.get(0); // save at somewhere temporarily
            this.sessionKey = (byte[]) list.get(1); // save SS session key somewhere on client side

            return true;
        } catch (StreamCorruptedException ex) {
            throw new CouldNotPerformException("The password you have entered was wrong. Please try again!");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();

            if (cause instanceof NotAvailableException) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable((NotAvailableException) cause, LOGGER, LogLevel.ERROR);
            }

            if (cause instanceof StreamCorruptedException) {
                ExceptionPrinter.printHistory(cause, LOGGER, LogLevel.ERROR);
                throw new CouldNotPerformException("Decryption failed. Please login again.", cause);
            }

            // RejectedException is thrown if the timestamp in the Authenticator does not fit to time period in TGT
            // or, if the clientID in Authenticator does not match the clientID in the TGT.
            // This should never occur, as Authenticator and Ticket are generated by the client handler for the same user.
            if (cause instanceof RejectedException) {
                throw new FatalImplementationErrorException(this, cause);
            }

            ExceptionPrinter.printHistory(cause, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Internal server error.", cause);
        } catch (CouldNotPerformException | IOException | InterruptedException ex) {
            throw new CouldNotPerformException("Login failed! Please try again.", ex);
        }
    }

    /**
     * Logs a user out by setting CST and session key to null
     */
    public void logout() {
        this.ticketAuthenticatorWrapper = null;
        this.sessionKey = null;
    }

    /**
     * determines if a user is logged in (does not validate ClientServerTicket and SessionKey)
     *
     * @return Returns true if logged in otherwise false
     */
    public boolean isLoggedIn() {
        return this.ticketAuthenticatorWrapper != null && this.sessionKey != null;
    }

    /**
     * Changes the login credentials for a given user.
     *
     * @param clientId ID of the user / client whose credentials should be changed.
     * @param oldCredentials Old credentials, needed for verification.
     * @param newCredentials New credentials to be set.
     * @throws CouldNotPerformException
     */
    public void changeCredentials(String clientId, String oldCredentials, String newCredentials) throws CouldNotPerformException {
        if (!this.isLoggedIn()) {
            throw new CouldNotPerformException("Please log in first!");
        }

        try {
            ticketAuthenticatorWrapper = AuthenticationClientHandler.initServiceServerRequest(sessionKey, ticketAuthenticatorWrapper);
            byte[] oldHash = EncryptionHelper.hash(oldCredentials);
            byte[] newHash = EncryptionHelper.hash(newCredentials);

            LoginCredentialsType.LoginCredentials loginCredentials = LoginCredentialsType.LoginCredentials.newBuilder()
                    .setId(clientId)
                    .setOldCredentials(EncryptionHelper.encrypt(oldHash, sessionKey))
                    .setNewCredentials(EncryptionHelper.encrypt(newHash, sessionKey))
                    .setTicketAuthenticatorWrapper(ticketAuthenticatorWrapper)
                    .build();

            TicketAuthenticatorWrapper newTicketAuthenticatorWrapper = CachedAuthenticationRemote.getRemote().changeCredentials(loginCredentials).get();
            AuthenticationClientHandler.handleServiceServerResponse(sessionKey, ticketAuthenticatorWrapper, newTicketAuthenticatorWrapper);
        } catch (IOException ex) {
            this.logout();
            ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Decryption failed. You have been logged out for security reasons. Please log in again.");
        } catch (RejectedException | NotAvailableException ex) {
            throw ExceptionPrinter.printHistoryAndReturnThrowable(ex, LOGGER, LogLevel.ERROR);
        } catch (InterruptedException ex) {
            ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Action was interrupted.", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();

            if (cause instanceof RejectedException) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable((RejectedException) cause, LOGGER, LogLevel.ERROR);
            }

            if (cause instanceof PermissionDeniedException) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable((PermissionDeniedException) cause, LOGGER, LogLevel.ERROR);
            }

            ExceptionPrinter.printHistory(cause, LOGGER, LogLevel.ERROR);
            throw new CouldNotPerformException("Internal server error.", cause);
        }
    }
}
