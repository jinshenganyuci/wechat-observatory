package bridge

import (
	"context"
	"errors"
	"strings"
	"time"
)

const clientPresenceInterval = 25 * time.Second

// ActiveClientPresenceStore commits both views of presence under the account
// lock. receivedAt is the server's receive time, never a client-supplied clock.
type ActiveClientPresenceStore interface {
	RecordActiveClientPresence(context.Context, AccountBinding, string, time.Time) error
}

type clientReceiptKey struct{}

func withClientReceipt(ctx context.Context, receivedAt time.Time) context.Context {
	return context.WithValue(ctx, clientReceiptKey{}, receivedAt)
}

func clientReceipt(ctx context.Context) time.Time {
	receivedAt, _ := ctx.Value(clientReceiptKey{}).(time.Time)
	return receivedAt
}

// ObserveModuleClientActivity is called only for an actual, valid client frame.
// Server polls and lease renewals must never call this method. Rechecking the
// current binding prevents a displaced socket from keeping its installation live.
func (s *Service) ObserveModuleClientActivity(ctx context.Context, apiKey, owner, session string, receivedAt time.Time) error {
	return s.withModuleAccount(ctx, apiKey, owner, session, false, func(ctx context.Context, owner string) error {
		return s.observeClientPresenceLocked(ctx, apiKey, owner, session, receivedAt)
	})
}

// Caller holds the device lock and has validated the current account session.
func (s *Service) observeClientPresenceLocked(ctx context.Context, apiKey, owner, session string, receivedAt time.Time) error {
	apiKey = strings.TrimSpace(apiKey)
	if receivedAt.IsZero() || receivedAt.After(s.now()) {
		return errors.New("invalid client receipt time")
	}
	// Legacy callers have no installation to renew. Their real HTTP poll/ACK
	// receipts continue to update runtime activity through RecordModuleActivity.
	if session == "" {
		return nil
	}
	auth, err := s.authorizeModuleAPIKey(ctx, apiKey)
	if err != nil {
		return err
	}
	binding, protected, err := s.currentBinding(ctx, auth.Device)
	if err != nil {
		return err
	}
	if !protected || binding.SessionID != session || binding.OwnerWxID != owner ||
		binding.CredentialID != auth.Key.CredentialID || binding.AuthVersion != auth.Key.AuthVersion {
		return ErrAccountSession
	}
	previous, found, err := s.findInstallation(ctx, binding.Device, binding.InstanceID)
	if err != nil {
		return err
	}
	// Compare receive timestamps, not processing time. Delayed/out-of-order
	// frames cannot rewind or rejuvenate presence. The durable timestamp also
	// makes this throttle work across server replicas.
	if found && receivedAt.Sub(previous.LastSeenAt) < clientPresenceInterval {
		return nil
	}
	if s.persistence != nil {
		store, ok := s.persistence.(ActiveClientPresenceStore)
		if !ok {
			return errors.New("durable client presence is unavailable")
		}
		return store.RecordActiveClientPresence(ctx, binding, apiKey, receivedAt)
	}
	return s.touchInstallation(ctx, InstallationSeen{Device: binding.Device, InstanceID: binding.InstanceID,
		OwnerWxID: binding.OwnerWxID, State: InstallationStateActive, Seen: receivedAt})
}
