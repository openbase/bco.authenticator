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
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.util.Arrays;
import java.util.concurrent.Future;
import org.openbase.bco.authentication.core.mock.MockServerStore;
import javax.crypto.BadPaddingException;
import org.openbase.bco.authentication.lib.AuthenticationServerHandler;
import org.openbase.bco.authentication.lib.EncryptionHelper;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.InitializationException;
import org.openbase.jul.iface.Launchable;
import org.openbase.jul.iface.VoidInitializable;
import org.openbase.bco.authentication.lib.jp.JPAuthenticationScope;
import org.openbase.jps.core.JPService;
import org.openbase.jps.exception.JPNotAvailableException;
import org.openbase.jul.extension.rsb.com.NotInitializedRSBLocalServer;
import org.openbase.jul.extension.rsb.com.RPCHelper;
import org.openbase.jul.extension.rsb.com.RSBFactoryImpl;
import org.openbase.jul.extension.rsb.com.RSBSharedConnectionConfig;
import org.openbase.jul.extension.rsb.iface.RSBLocalServer;
import org.openbase.jul.schedule.GlobalCachedExecutorService;
import org.openbase.jul.schedule.WatchDog;
import rst.domotic.authentication.TicketAuthenticatorWrapperType.TicketAuthenticatorWrapper;
import rst.domotic.authentication.TicketSessionKeyWrapperType.TicketSessionKeyWrapper;
import org.openbase.bco.authentication.lib.AuthenticationService;
import org.openbase.bco.authentication.lib.Store;
import org.openbase.bco.authentication.lib.jp.JPAuthenticationSimulationMode;
import org.openbase.jul.exception.NotAvailableException;
import org.openbase.jul.exception.PermissionDeniedException;
import org.openbase.jul.exception.RejectedException;
import org.openbase.jul.exception.printer.ExceptionPrinter;
import org.openbase.jul.exception.printer.LogLevel;
import org.slf4j.LoggerFactory;
import rsb.converter.DefaultConverterRepository;
import rsb.converter.ProtocolBufferConverter;
import rst.domotic.authentication.AuthenticatorType.Authenticator;
import rst.domotic.authentication.LoginCredentialsChangeType.LoginCredentialsChange;
import rst.domotic.authentication.TicketType.Ticket;

/**
 *
 * @author <a href="mailto:thuxohl@techfak.uni-bielefeld.de">Tamino Huxohl</a>
 */
public class AuthenticatorController implements AuthenticationService, Launchable<Void>, VoidInitializable {

    static {
        DefaultConverterRepository.getDefaultConverterRepository().addConverter(new ProtocolBufferConverter<>(TicketSessionKeyWrapper.getDefaultInstance()));
        DefaultConverterRepository.getDefaultConverterRepository().addConverter(new ProtocolBufferConverter<>(TicketAuthenticatorWrapper.getDefaultInstance()));
        DefaultConverterRepository.getDefaultConverterRepository().addConverter(new ProtocolBufferConverter<>(LoginCredentialsChange.getDefaultInstance()));
    }

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(AuthenticatorController.class);

    private RSBLocalServer server;
    private WatchDog serverWatchDog;

    private final byte[] ticketGrantingServicePrivateKey;
    private final byte[] serviceServerPrivateKey;

    private final Store store;

    public AuthenticatorController() {
        this(new ServerStore());
    }

    public AuthenticatorController(Store authenticationRegistry) {
        this.server = new NotInitializedRSBLocalServer();

        this.ticketGrantingServicePrivateKey = EncryptionHelper.generateKey();
        this.serviceServerPrivateKey = EncryptionHelper.generateKey();

        boolean simulation = false;
        try {
            simulation = JPService.getProperty(JPAuthenticationSimulationMode.class).getValue();
        } catch (JPNotAvailableException ex) {
            LOGGER.warn("Could not check simulation property. Starting in normal mode.", ex);
        }
        if (simulation) {
            this.store = new MockServerStore();
        } else {
            this.store = authenticationRegistry;
        }
    }

    @Override
    public void init() throws InitializationException, InterruptedException {
        try {
            server = RSBFactoryImpl.getInstance().createSynchronizedLocalServer(JPService.getProperty(JPAuthenticationScope.class).getValue(), RSBSharedConnectionConfig.getParticipantConfig());

            // register rpc methods.
            RPCHelper.registerInterface(AuthenticationService.class, this, server);

            serverWatchDog = new WatchDog(server, "AuthenticatorWatchDog");
        } catch (JPNotAvailableException | CouldNotPerformException ex) {
            throw new InitializationException(this, ex);
        }

        store.init();
    }

    @Override
    public void activate() throws CouldNotPerformException, InterruptedException {
        serverWatchDog.activate();
    }

    @Override
    public void deactivate() throws CouldNotPerformException, InterruptedException {
        if (serverWatchDog != null) {
            serverWatchDog.deactivate();
        }
    }

    @Override
    public boolean isActive() {
        if (serverWatchDog != null) {
            return serverWatchDog.isActive();
        } else {
            return false;
        }
    }

    public void waitForActivation() throws CouldNotPerformException, InterruptedException {
        try {
            serverWatchDog.waitForServiceActivation();
        } catch (final CouldNotPerformException ex) {
            throw new CouldNotPerformException("Could not wait for activation!", ex);
        }
    }

    @Override
    public Future<TicketSessionKeyWrapper> requestTicketGrantingTicket(String id) throws CouldNotPerformException {
        return GlobalCachedExecutorService.submit(() -> {
            try {
                String[] split = id.split("@", 2);
                String _id;
                byte[] key;
                boolean isUser = false;
                if (split[0].length() > 0) {
                    isUser = true;
                    _id = split[0].trim();
                    key = store.getCredentials(_id);
                }
                else {
                    _id = split[1].trim();
                    key = store.getCredentials(_id);
                }
                return AuthenticationServerHandler.handleKDCRequest(_id, key, isUser, "", ticketGrantingServicePrivateKey);
            } catch (NotAvailableException ex) {
                throw ExceptionPrinter.printHistoryAndReturnThrowable(ex, LOGGER, LogLevel.ERROR);
            } catch (InterruptedException | CouldNotPerformException | IOException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
                throw new CouldNotPerformException("Internal server error. Please try again.");
            }
        });
    }

    @Override
    public Future<TicketSessionKeyWrapper> requestClientServerTicket(TicketAuthenticatorWrapper ticketAuthenticatorWrapper) throws CouldNotPerformException {
        return GlobalCachedExecutorService.submit(() -> {
            try {
                return AuthenticationServerHandler.handleTGSRequest(ticketGrantingServicePrivateKey, serviceServerPrivateKey, ticketAuthenticatorWrapper);
            } catch (RejectedException | BadPaddingException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.WARN);
                throw ex;
            } catch (IOException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
                throw new CouldNotPerformException("Internal server error. Please try again.");
            }
        });
    }

    @Override
    public Future<TicketAuthenticatorWrapper> validateClientServerTicket(TicketAuthenticatorWrapper ticketAuthenticatorWrapper) throws CouldNotPerformException {
        return GlobalCachedExecutorService.submit(() -> {
            try {
                return AuthenticationServerHandler.handleSSRequest(serviceServerPrivateKey, ticketAuthenticatorWrapper);
            } catch (RejectedException | BadPaddingException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.WARN);
                throw ex;
            } catch (IOException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
                throw new CouldNotPerformException("Internal server error. Please try again.");
            }
        });
    }

    @Override
    public Future<TicketAuthenticatorWrapper> changeCredentials(LoginCredentialsChange loginCredentialsChange) throws RejectedException, IOException {
        return GlobalCachedExecutorService.submit(() -> {
            try {
                // Validate the given authenticator and ticket.
                TicketAuthenticatorWrapper wrapper = loginCredentialsChange.getTicketAuthenticatorWrapper();
                TicketAuthenticatorWrapper response = AuthenticationServerHandler.handleSSRequest(serviceServerPrivateKey, wrapper);

                // Decrypt ticket, authenticator and credentials.
                Ticket clientServerTicket = EncryptionHelper.decryptSymmetric(wrapper.getTicket(), serviceServerPrivateKey, Ticket.class);
                byte[] clientServerSessionKey = clientServerTicket.getSessionKeyBytes().toByteArray();
                Authenticator authenticator = EncryptionHelper.decryptSymmetric(wrapper.getAuthenticator(), clientServerSessionKey, Authenticator.class);
                byte[] oldCredentials = EncryptionHelper.decryptSymmetric(loginCredentialsChange.getOldCredentials(), clientServerSessionKey, byte[].class);
                byte[] newCredentials = EncryptionHelper.decryptSymmetric(loginCredentialsChange.getNewCredentials(), clientServerSessionKey, byte[].class);
                String userId = loginCredentialsChange.getId();
                String[] split = authenticator.getClientId().split("@", 2);
                String authenticatorUserId = split[0];

                // Allow users to change their own password.
                if (!userId.equals(authenticatorUserId)) {
                    throw new PermissionDeniedException("You are not permitted to perform this action.");
                }

                // Check if the given credentials correspond to the old one.
                if (!Arrays.equals(oldCredentials, store.getCredentials(userId))) {
                    throw new RejectedException("The old password is wrong.");
                }

                // Update credentials.
                store.setCredentials(userId, newCredentials);

                return response;
            } catch (RejectedException | BadPaddingException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.WARN);
                throw ex;
            } catch (IOException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
                throw ex;
            }
        });
    }

    @Override
    public Future<TicketAuthenticatorWrapper> register(LoginCredentialsChange loginCredentialsChange) throws RejectedException, StreamCorruptedException, IOException {
        return GlobalCachedExecutorService.submit(() -> {
            try {
                // validate the given authenticator and ticket.
                TicketAuthenticatorWrapper wrapper = loginCredentialsChange.getTicketAuthenticatorWrapper();
                TicketAuthenticatorWrapper response = AuthenticationServerHandler.handleSSRequest(serviceServerPrivateKey, wrapper);

                // decrypt ticket and authenticator
                Ticket clientServerTicket = EncryptionHelper.decryptSymmetric(wrapper.getTicket(), serviceServerPrivateKey, Ticket.class);
                byte[] clientServerSessionKey = clientServerTicket.getSessionKeyBytes().toByteArray();
                
                Authenticator authenticator = EncryptionHelper.decryptSymmetric(wrapper.getAuthenticator(), clientServerSessionKey, Authenticator.class);
                String authenticatorUserId = authenticator.getClientId().split("@", 2)[0];
                String newId = loginCredentialsChange.getId();
                
                // check if performing user is admin
                if (!store.isAdmin(authenticatorUserId))
                    throw new PermissionDeniedException("You are not permitted to perform this action.");
                
                // don't allow administrators to overwrite themselves
                // don't allow to overwrite somebody else
                if (newId.equals(authenticatorUserId) || store.hasEntry(newId))
                    throw new CouldNotPerformException("You cannot register an existing user.");
                
                // decrypt key
                byte[] key = EncryptionHelper.decryptSymmetric(loginCredentialsChange.getNewCredentials(), clientServerSessionKey, byte[].class);

                // register
                store.addCredentials(newId, key, loginCredentialsChange.getAdmin());
                
                return response;
            } catch (RejectedException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.WARN);
                throw new RejectedException(ex.getMessage());
            } catch (StreamCorruptedException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.WARN);
                throw new StreamCorruptedException(ex.getMessage());
            } catch (IOException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
                throw new CouldNotPerformException("Internal server error. Please try again.");
            }
        });
    }

    @Override
    public Future<TicketAuthenticatorWrapper> setAdministrator(LoginCredentialsChange loginCredentialsChange) throws RejectedException, StreamCorruptedException, IOException {
        return GlobalCachedExecutorService.submit(() -> {
            try {
                // validate the given authenticator and ticket.
                TicketAuthenticatorWrapper wrapper = loginCredentialsChange.getTicketAuthenticatorWrapper();
                TicketAuthenticatorWrapper response = AuthenticationServerHandler.handleSSRequest(serviceServerPrivateKey, wrapper);

                // decrypt ticket and authenticator
                Ticket clientServerTicket = EncryptionHelper.decryptSymmetric(wrapper.getTicket(), serviceServerPrivateKey, Ticket.class);
                byte[] clientServerSessionKey = clientServerTicket.getSessionKeyBytes().toByteArray();
                
                Authenticator authenticator = EncryptionHelper.decryptSymmetric(wrapper.getAuthenticator(), clientServerSessionKey, Authenticator.class);
                String authenticatorUserId = authenticator.getClientId().split("@", 2)[0];
                String newId = loginCredentialsChange.getId();
                
                // check if performing user is admin
                if (!this.store.isAdmin(authenticatorUserId))
                    throw new PermissionDeniedException("You are not permitted to perform this action.");
                
                // don't allow administrators to change administrator status of themselves
                if (newId.equals(authenticatorUserId))
                    throw new CouldNotPerformException("You cannot register an existing user.");
                
                // don't allow administrators to change administrator status of nonexistent entry
                if (!this.store.hasEntry(newId))
                    throw new CouldNotPerformException("Given user does not exist.");
            
                // register
                this.store.setAdmin(newId, loginCredentialsChange.getAdmin());
                
                return response;
            } catch (RejectedException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.WARN);
                throw new RejectedException(ex.getMessage());
            } catch (StreamCorruptedException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.WARN);
                throw new StreamCorruptedException(ex.getMessage());
            } catch (IOException ex) {
                ExceptionPrinter.printHistory(ex, LOGGER, LogLevel.ERROR);
                throw new CouldNotPerformException("Internal server error. Please try again.");
            }
        });
    }

    @Override
    public Future<Void> registerClient(LoginCredentialsChange loginCredentialsChange) throws CouldNotPerformException {
        return GlobalCachedExecutorService.submit(() -> {
            store.setCredentials(loginCredentialsChange.getId(), loginCredentialsChange.getNewCredentials().toByteArray());
            return null;
        });
    }

    @Override
    public Future<Boolean> isInRegistrationMode() throws CouldNotPerformException {
        return GlobalCachedExecutorService.submit(() -> false);
    }
}
