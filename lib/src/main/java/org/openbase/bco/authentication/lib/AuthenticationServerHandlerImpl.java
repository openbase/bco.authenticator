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
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.NotAvailableException;
import org.openbase.jul.exception.RejectedException;
import rst.domotic.authentication.TicketAuthenticatorWrapperType.TicketAuthenticatorWrapper;
import rst.domotic.authentication.AuthenticatorType.Authenticator;
import rst.domotic.authentication.TicketSessionKeyWrapperType.TicketSessionKeyWrapper;
import rst.domotic.authentication.TicketType.Ticket;
import rst.timing.IntervalType.Interval;
import rst.timing.TimestampType.Timestamp;

/**
 *
 * @author Sebastian Fast <sfast@techfak.uni-bielefeld.de>
 */
public class AuthenticationServerHandlerImpl implements AuthenticationServerHandler {

    @Override
    public TicketSessionKeyWrapper handleKDCRequest(String clientID, byte[] clientPassword, String clientNetworkAddress, byte[] TGSSessionKey, byte[] TGSPrivateKey) throws NotAvailableException, InterruptedException, CouldNotPerformException, IOException {
//        String[] split = clientID.split("@", 2);
//        String userName = split[0];
//        final UserRegistryRemote userRegistry = Registries.getUserRegistry();
//
//        userRegistry.waitForData();
//        UnitConfig userConfig = userRegistry.getUserConfigByUserName(userName);

        // set period
        long start = System.currentTimeMillis();
        long end = start + (5 * 60 * 1000);
        Interval.Builder ib = Interval.newBuilder();
        Timestamp.Builder tb = Timestamp.newBuilder();
        tb.setTime(start);
        ib.setBegin(tb.build());
        tb.setTime(end);
        ib.setEnd(tb.build());

        // create tgt
        Ticket.Builder tgtb = Ticket.newBuilder();
        tgtb.setClientId(clientID);
        tgtb.setClientIp(clientNetworkAddress);
        tgtb.setValidityPeriod(ib.build());
        tgtb.setSessionKey(TGSSessionKey.toString());

        // create TicketSessionKeyWrapper
        TicketSessionKeyWrapper.Builder wb = TicketSessionKeyWrapper.newBuilder();
        wb.setTicket(EncryptionHelper.encrypt(tgtb.build(), TGSPrivateKey));
        // TODO: wb.setSessionKey(EncryptionHelper.encrypt(TGSSessionKey, userConfig.getUserConfig().getPassword()));
         TODO: wb.setSessionKey(EncryptionHelper.encrypt(TGSSessionKey, EncryptionHelper.hash("password")));

        return wb.build();
    }

    @Override
    public TicketSessionKeyWrapper handleTGSRequest(byte[] TGSSessionKey, byte[] TGSPrivateKey, byte[] SSSessionKey, byte[] SSPrivateKey, TicketAuthenticatorWrapper wrapper) throws RejectedException, StreamCorruptedException, IOException {
        // decrypt ticket and authenticator
        Ticket TGT = (Ticket) EncryptionHelper.decrypt(wrapper.getTicket(), TGSPrivateKey);
        Authenticator authenticator = (Authenticator) EncryptionHelper.decrypt(wrapper.getAuthenticator(), TGSSessionKey);

        // compare clientIDs and timestamp to period
        this.validateTicket(TGT, authenticator);

        // set period
        long start = System.currentTimeMillis();
        long end = start + (5 * 60 * 1000);
        Interval.Builder ib = Interval.newBuilder();
        Timestamp.Builder tb = Timestamp.newBuilder();
        tb.setTime(start);
        ib.setBegin(tb.build());
        tb.setTime(end);
        ib.setEnd(tb.build());

        // update period and session key
        Ticket.Builder cstb = TGT.toBuilder();
        cstb.setValidityPeriod(ib.build());
        cstb.setSessionKey(SSSessionKey.toString());

        // create TicketSessionKeyWrapper
        TicketSessionKeyWrapper.Builder wb = TicketSessionKeyWrapper.newBuilder();
        wb.setTicket(EncryptionHelper.encrypt(cstb.build(), SSPrivateKey));
        wb.setSessionKey(EncryptionHelper.encrypt(SSSessionKey, TGSSessionKey));

        return wb.build();
    }

    @Override
    public TicketAuthenticatorWrapper handleSSRequest(byte[] SSSessionKey, byte[] SSPrivateKey, TicketAuthenticatorWrapper wrapper) throws RejectedException, StreamCorruptedException, IOException {
        // decrypt ticket and authenticator
        Ticket CST = (Ticket) EncryptionHelper.decrypt(wrapper.getTicket(), SSPrivateKey);
        Authenticator authenticator = (Authenticator) EncryptionHelper.decrypt(wrapper.getAuthenticator(), SSSessionKey);

        // compare clientIDs and timestamp to period
        this.validateTicket(CST, authenticator);

        // set period
        long start = System.currentTimeMillis();
        long end = start + (5 * 60 * 1000);
        Interval.Builder ib = Interval.newBuilder();
        Timestamp.Builder tb = Timestamp.newBuilder();
        tb.setTime(start);
        ib.setBegin(tb.build());
        tb.setTime(end);
        ib.setEnd(tb.build());

        // update period and session key
        Ticket.Builder cstb = CST.toBuilder();
        cstb.setValidityPeriod(ib.build());

        // update TicketAuthenticatorWrapper
        TicketAuthenticatorWrapper.Builder atb = wrapper.toBuilder();
        atb.setTicket(EncryptionHelper.encrypt(CST, SSPrivateKey));

        return atb.build();
    }

    private void validateTicket(Ticket ticket, Authenticator authenticator) throws RejectedException {
        if (ticket.getClientId() == null) {
            throw new RejectedException("ClientId null in ticket");
        }
        if (authenticator.getClientId() == null) {
            throw new RejectedException("ClientId null in authenticator");
        }
        if (!authenticator.getClientId().equals(ticket.getClientId())) {
            throw new RejectedException("ClientIds do not match");
        }
        if (!this.isTimestampInInterval(authenticator.getTimestamp(), ticket.getValidityPeriod())) {
            throw new RejectedException("Session expired");
        }
    }

    private boolean isTimestampInInterval(Timestamp timestamp, Interval interval) {
        return true;
    }
}
