package bridge

import (
	"bufio"
	"context"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	websocketGUID                    = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
	wsOpText                    byte = 0x1
	wsOpClose                   byte = 0x8
	wsOpPing                    byte = 0x9
	wsOpPong                    byte = 0xA
	wsMaxPayload                     = 1 << 20
	outboxWSProbeTimeout             = 3 * time.Second
	outboxWSActivityFreshness        = 30 * time.Second
	outboxWSAckTimeout               = 65 * time.Second
	outboxWSAckOperationTimeout      = 5 * time.Second
	outboxWSPresenceTimeout          = 2 * time.Second
)

type outboxWSMessage struct {
	Type  string             `json:"type"`
	OK    bool               `json:"ok,omitempty"`
	Time  int64              `json:"time,omitempty"`
	Error string             `json:"error,omitempty"`
	Items []ModuleOutboxItem `json:"items,omitempty"`
	Ack   *ModuleAckRequest  `json:"ack,omitempty"`
}

func (s *HTTPServer) outboxWebSocket(w http.ResponseWriter, r *http.Request) {
	s.outboxWebSocketWithAckTimeout(w, r, outboxWSAckTimeout)
}

func (s *HTTPServer) outboxWebSocketWithAckTimeout(w http.ResponseWriter, r *http.Request, ackTimeout time.Duration) {
	apiKey := strings.TrimSpace(r.URL.Query().Get("api_key"))
	device := strings.TrimSpace(r.URL.Query().Get("device"))
	wxid := strings.TrimSpace(r.URL.Query().Get("wxid"))
	accountSession := strings.TrimSpace(r.URL.Query().Get("account_session"))
	if device == "" {
		device = s.service.DefaultDevice()
	}
	auth, err := s.service.authorizeModuleAPIKey(r.Context(), apiKey)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized", err.Error())
		return
	}
	device = auth.Device
	session, err := s.service.AcquireOutboxSession(r.Context(), apiKey, device, wxid, accountSession)
	if err != nil {
		if errors.Is(err, ErrAccountSession) {
			writeError(w, http.StatusConflict, "account_session_conflict", err.Error())
			return
		}
		if errors.Is(err, ErrModuleSessionActive) {
			writeError(w, http.StatusConflict, "device_session_active", "device session is active; retry shortly")
			return
		}
		writeError(w, http.StatusUnauthorized, "unauthorized", err.Error())
		return
	}
	conn, err := upgradeWebSocket(w, r)
	if err != nil {
		s.service.ReleaseOutboxSession(context.Background(), session)
		writeError(w, http.StatusBadRequest, "websocket_upgrade_failed", err.Error())
		return
	}
	defer func() {
		// Do not leave the old transport open while releasing a durable lease.
		conn.close()
		releaseCtx, done := context.WithTimeout(context.Background(), 3*time.Second)
		defer done()
		s.service.ReleaseOutboxSession(releaseCtx, session)
	}()

	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	notify, unsubscribe := s.service.subscribeOutbox(device)
	defer unsubscribe()

	outgoing := make(chan outboxWSMessage, 8)
	activity := make(chan time.Time, 1)
	pong := make(chan time.Time, 1)
	go s.readOutboxWS(ctx, cancel, conn, apiKey, device, wxid, accountSession, outgoing, activity, pong)

	outgoing <- outboxWSMessage{Type: "ready", OK: true, Time: time.Now().Unix()}
	outgoing <- outboxWSMessage{Type: "probe"}
	ping := time.NewTicker(25 * time.Second)
	defer ping.Stop()
	poll := time.NewTicker(s.service.OutboxPollInterval())
	defer poll.Stop()
	renewInterval := session.TTL / 3
	if renewInterval <= 0 {
		renewInterval = 5 * time.Second
	}
	renew := time.NewTicker(renewInterval)
	defer renew.Stop()

	var lastInbound time.Time
	var inFlightID int64
	var ackTimer *time.Timer
	var ackDeadline <-chan time.Time
	stopAck := func() {
		if ackTimer != nil && !ackTimer.Stop() {
			select {
			case <-ackTimer.C:
			default:
			}
		}
		ackTimer = nil
		ackDeadline = nil
	}
	defer stopAck()
	var deliveryRequested bool
	var probeTimer *time.Timer
	var probeDeadline <-chan time.Time
	var probeStarted time.Time
	stopProbe := func() {
		if probeTimer != nil && !probeTimer.Stop() {
			select {
			case <-probeTimer.C:
			default:
			}
		}
		probeTimer = nil
		probeDeadline = nil
	}
	defer stopProbe()
	startProbe := func() bool {
		if probeDeadline != nil {
			return true
		}
		probeStarted = s.service.now()
		if !conn.writeControl(wsOpPing, []byte("outbox-probe")) {
			return false
		}
		probeTimer = time.NewTimer(outboxWSProbeTimeout)
		probeDeadline = probeTimer.C
		return true
	}
	deliver := func() bool {
		deliveryRequested = false
		items, err := s.service.PollOutbox(ctx, ModulePollRequest{APIKey: apiKey, Device: device, WxID: wxid, AccountSession: accountSession, Limit: 1})
		if err != nil {
			if !conn.writeJSON(outboxWSMessage{Type: "error", Error: err.Error(), Time: time.Now().Unix()}) {
				return false
			}
			return !isModuleAuthError(err)
		}
		if len(items) == 0 {
			return true
		}
		started := time.Now()
		if !conn.writeJSON(outboxWSMessage{Type: "outbox", OK: true, Items: items, Time: time.Now().Unix()}) {
			return false
		}
		for _, item := range items {
			s.service.traceOutbox(outboxTraceWSWritten, item.ID, item.AttemptCount, time.Since(started))
		}
		inFlightID = items[0].ID
		ackTimer = time.NewTimer(ackTimeout)
		ackDeadline = ackTimer.C
		return true
	}
	requestDelivery := func(forceProbe bool) bool {
		deliveryRequested = true
		if inFlightID != 0 || probeDeadline != nil {
			return true
		}
		if forceProbe || lastInbound.IsZero() || s.service.now().Sub(lastInbound) > outboxWSActivityFreshness {
			return startProbe()
		}
		return deliver()
	}

	for {
		select {
		case <-ctx.Done():
			return
		case receivedAt := <-activity:
			if receivedAt.After(lastInbound) {
				lastInbound = receivedAt
			}
		case receivedAt := <-pong:
			if receivedAt.After(lastInbound) {
				lastInbound = receivedAt
			}
			if probeDeadline != nil && !receivedAt.Before(probeStarted) {
				stopProbe()
				if deliveryRequested && inFlightID == 0 && !deliver() {
					return
				}
			}
		case <-probeDeadline:
			return
		case <-ackDeadline:
			// Heartbeats cannot hold the queue indefinitely without a task ACK.
			// Preserve the existing 60s durable lease: never blindly release or
			// send another task while the previous native send is uncertain.
			log.Printf("module outbox acknowledgement timeout device=%q task_id=%d", device, inFlightID)
			return
		case msg := <-outgoing:
			switch msg.Type {
			case "probe":
				if !requestDelivery(true) {
					return
				}
				continue
			case "wake":
				if !requestDelivery(false) {
					return
				}
				continue
			case "ack":
				if msg.OK && outboxAcknowledgesFlight(msg.Items, inFlightID) {
					inFlightID = 0
					stopAck()
				}
			}
			if !conn.writeJSON(msg) {
				return
			}
		case <-notify:
			if !requestDelivery(false) {
				return
			}
		case <-poll.C:
			if !requestDelivery(false) {
				return
			}
		case <-renew.C:
			active, err := s.service.RenewOutboxSession(ctx, session)
			if err != nil || !active {
				_ = conn.writeJSON(outboxWSMessage{Type: "error", Error: "device session lease lost", Time: time.Now().Unix()})
				return
			}
		case <-ping.C:
			if !conn.writeControl(wsOpPing, []byte("ping")) {
				return
			}
			if !requestDelivery(false) {
				return
			}
		}
	}
}

func outboxAcknowledgesFlight(items []ModuleOutboxItem, id int64) bool {
	if id == 0 {
		return false
	}
	for _, item := range items {
		if item.ID == id && (item.Status == "sent" || item.Status == "failed") {
			return true
		}
	}
	return false
}

func (s *HTTPServer) readOutboxWS(
	ctx context.Context,
	cancel context.CancelFunc,
	conn *wsConn,
	apiKey string,
	device string,
	wxid string,
	accountSession string,
	outgoing chan<- outboxWSMessage,
	activity chan time.Time,
	pong chan time.Time,
) {
	defer cancel()
	presence := make(chan time.Time, 1)
	var receiptMu sync.Mutex
	recordReceipt := func(receivedAt time.Time) {
		// The reader and ACK worker share these two coalescing mailboxes.
		receiptMu.Lock()
		defer receiptMu.Unlock()
		publishClientReceipt(activity, receivedAt)
		publishClientReceipt(presence, receivedAt)
	}
	go observeClientReceipts(ctx, cancel, presence, func(ctx context.Context, receivedAt time.Time) error {
		err := s.service.ObserveModuleClientActivity(ctx, apiKey, wxid, accountSession, receivedAt)
		if err != nil && ctx.Err() == nil {
			// Never log the API key, query string or database error payload.
			log.Printf("module client presence update rejected or unavailable device=%q", device)
		}
		return err
	})
	acks := make(chan outboxWSAckRequest, 4)
	go s.processOutboxWSAcks(ctx, cancel, acks, outgoing, recordReceipt)
	for {
		payload, op, err := conn.readFrame()
		if err != nil {
			return
		}
		receivedAt := s.service.now()
		switch op {
		case wsOpClose:
			return
		case wsOpPing:
			recordReceipt(receivedAt)
			if !conn.writeControl(wsOpPong, payload) {
				return
			}
		case wsOpPong:
			publishClientReceipt(pong, receivedAt)
			recordReceipt(receivedAt)
			continue
		case wsOpText:
			var msg outboxWSMessage
			if err := json.Unmarshal(payload, &msg); err != nil {
				if !sendOutboxWSMessage(ctx, outgoing, outboxWSMessage{Type: "error", Error: "invalid json: " + err.Error(), Time: time.Now().Unix()}) {
					return
				}
				continue
			}
			switch strings.ToLower(strings.TrimSpace(msg.Type)) {
			case "ack":
				if msg.Ack == nil {
					if !sendOutboxWSMessage(ctx, outgoing, outboxWSMessage{Type: "error", Error: "ack payload is required", Time: time.Now().Unix()}) {
						return
					}
					continue
				}
				msg.Ack.APIKey = apiKey
				msg.Ack.Device = device
				msg.Ack.WxID = wxid
				msg.Ack.AccountSession = accountSession
				for _, item := range msg.Ack.Items {
					s.service.traceOutbox(outboxTraceAckReceived, item.ID, 0, 0)
				}
				for _, id := range msg.Ack.IDs {
					s.service.traceOutbox(outboxTraceAckReceived, id, 0, 0)
				}
				select {
				case acks <- outboxWSAckRequest{request: *msg.Ack, receivedAt: receivedAt}:
				case <-ctx.Done():
					return
				default:
					// Bound queued database work. The module retains the result
					// and can retry its ACK after reconnecting, without resending.
					return
				}
			case "poll", "wake":
				recordReceipt(receivedAt)
				if !sendOutboxWSMessage(ctx, outgoing, outboxWSMessage{Type: "wake"}) {
					return
				}
			case "pong":
				recordReceipt(receivedAt)
				continue
			default:
				if !sendOutboxWSMessage(ctx, outgoing, outboxWSMessage{Type: "error", Error: "unknown message type", Time: time.Now().Unix()}) {
					return
				}
			}
		}
	}
}

type outboxWSAckRequest struct {
	request    ModuleAckRequest
	receivedAt time.Time
}

// One ordered, bounded database worker leaves the socket reader free to answer
// control frames while an ACK waits for a lock or durable storage. It never
// invokes the native sender or releases a task lease on an uncertain result.
func (s *HTTPServer) processOutboxWSAcks(ctx context.Context, cancel context.CancelFunc, requests <-chan outboxWSAckRequest,
	outgoing chan<- outboxWSMessage, recordReceipt func(time.Time)) {
	for {
		select {
		case <-ctx.Done():
			return
		case ack := <-requests:
			if ctx.Err() != nil {
				return
			}
			operation, done := context.WithTimeout(ctx, outboxWSAckOperationTimeout)
			items, err := s.service.AckOutbox(withClientReceipt(operation, ack.receivedAt), ack.request)
			done()
			if err != nil {
				if !sendOutboxWSMessage(ctx, outgoing, outboxWSMessage{Type: "error", Error: err.Error(), Time: time.Now().Unix()}) {
					return
				}
				if isModuleAuthError(err) {
					cancel()
					return
				}
				continue
			}
			recordReceipt(ack.receivedAt)
			if !sendOutboxWSMessage(ctx, outgoing, outboxWSMessage{Type: "ack", OK: true, Items: items, Time: time.Now().Unix()}) ||
				!sendOutboxWSMessage(ctx, outgoing, outboxWSMessage{Type: "wake"}) {
				return
			}
		}
	}
}

func sendOutboxWSMessage(ctx context.Context, outgoing chan<- outboxWSMessage, message outboxWSMessage) bool {
	select {
	case <-ctx.Done():
		return false
	case outgoing <- message:
		return true
	}
}

// One bounded worker per connection performs durable authentication/presence
// work. A busy database must not block the reader's ping/pong control path.
// Business delivery and ACK still independently validate the current session.
func observeClientReceipts(ctx context.Context, cancel context.CancelFunc, receipts <-chan time.Time,
	observe func(context.Context, time.Time) error) {
	for {
		select {
		case <-ctx.Done():
			return
		case receivedAt := <-receipts:
			if ctx.Err() != nil {
				return
			}
			operation, done := context.WithTimeout(ctx, outboxWSPresenceTimeout)
			err := observe(operation, receivedAt)
			done()
			if err != nil {
				if isModuleAuthError(err) {
					cancel()
					return
				}
				// A storage outage is not proof that the authenticated peer has
				// gone away. Retry only with a later actual receipt; the bounded
				// mailbox coalesces arrivals while we back off. Delivery/ACK
				// continue to perform their own authoritative session checks.
				timer := time.NewTimer(time.Second)
				select {
				case <-ctx.Done():
					timer.Stop()
					return
				case <-timer.C:
				}
			}
		}
	}
}

// Callers serialize producers. Keep the newest receipt when the writer is
// busy, so a pre-probe pong cannot crowd out the response to the current probe.
func publishClientReceipt(mailbox chan time.Time, receivedAt time.Time) {
	select {
	case mailbox <- receivedAt:
		return
	default:
	}
	select {
	case pending := <-mailbox:
		if pending.After(receivedAt) {
			receivedAt = pending
		}
	default:
	}
	select {
	case mailbox <- receivedAt:
	default:
	}
}

func isModuleAuthError(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, ErrAccountSession) {
		return true
	}
	lower := strings.ToLower(err.Error())
	return strings.Contains(lower, "invalid api key") ||
		strings.Contains(lower, "api key disabled") ||
		strings.Contains(lower, "api_key is required") ||
		strings.Contains(lower, "unknown device") ||
		strings.Contains(lower, "device is required")
}

type wsConn struct {
	conn    net.Conn
	rw      *bufio.ReadWriter
	writeMu sync.Mutex
}

func upgradeWebSocket(w http.ResponseWriter, r *http.Request) (*wsConn, error) {
	if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
		return nil, errors.New("missing websocket upgrade header")
	}
	if !headerHasToken(r.Header.Get("Connection"), "upgrade") {
		return nil, errors.New("missing connection upgrade header")
	}
	key := strings.TrimSpace(r.Header.Get("Sec-WebSocket-Key"))
	if key == "" {
		return nil, errors.New("missing Sec-WebSocket-Key")
	}
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		return nil, errors.New("server does not support hijacking")
	}
	conn, rw, err := hijacker.Hijack()
	if err != nil {
		return nil, err
	}
	accept := websocketAccept(key)
	response := "HTTP/1.1 101 Switching Protocols\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
	if _, err := rw.WriteString(response); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if err := rw.Flush(); err != nil {
		_ = conn.Close()
		return nil, err
	}
	return &wsConn{conn: conn, rw: rw}, nil
}

func websocketAccept(key string) string {
	sum := sha1.Sum([]byte(key + websocketGUID))
	return base64.StdEncoding.EncodeToString(sum[:])
}

func headerHasToken(raw, token string) bool {
	for _, part := range strings.Split(raw, ",") {
		if strings.EqualFold(strings.TrimSpace(part), token) {
			return true
		}
	}
	return false
}

func (c *wsConn) close() {
	_ = c.conn.Close()
}

func (c *wsConn) readFrame() ([]byte, byte, error) {
	header := make([]byte, 2)
	if _, err := io.ReadFull(c.rw, header); err != nil {
		return nil, 0, err
	}
	op := header[0] & 0x0F
	masked := header[1]&0x80 != 0
	length := uint64(header[1] & 0x7F)
	switch length {
	case 126:
		ext := make([]byte, 2)
		if _, err := io.ReadFull(c.rw, ext); err != nil {
			return nil, 0, err
		}
		length = uint64(binary.BigEndian.Uint16(ext))
	case 127:
		ext := make([]byte, 8)
		if _, err := io.ReadFull(c.rw, ext); err != nil {
			return nil, 0, err
		}
		length = binary.BigEndian.Uint64(ext)
	}
	if length > wsMaxPayload {
		return nil, 0, errors.New("websocket frame too large")
	}
	mask := []byte(nil)
	if masked {
		mask = make([]byte, 4)
		if _, err := io.ReadFull(c.rw, mask); err != nil {
			return nil, 0, err
		}
	}
	payload := make([]byte, int(length))
	if _, err := io.ReadFull(c.rw, payload); err != nil {
		return nil, 0, err
	}
	if masked {
		for i := range payload {
			payload[i] ^= mask[i%4]
		}
	}
	return payload, op, nil
}

func (c *wsConn) writeJSON(payload any) bool {
	data, err := json.Marshal(payload)
	if err != nil {
		return false
	}
	return c.writeFrame(wsOpText, data)
}

func (c *wsConn) writeControl(op byte, payload []byte) bool {
	if len(payload) > 125 {
		payload = payload[:125]
	}
	return c.writeFrame(op, payload)
}

func (c *wsConn) writeFrame(op byte, payload []byte) bool {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	header := []byte{0x80 | op}
	length := len(payload)
	switch {
	case length < 126:
		header = append(header, byte(length))
	case length <= 65535:
		header = append(header, 126, byte(length>>8), byte(length))
	default:
		header = append(header, 127, 0, 0, 0, 0, byte(length>>24), byte(length>>16), byte(length>>8), byte(length))
	}
	if _, err := c.rw.Write(header); err != nil {
		return false
	}
	if _, err := c.rw.Write(payload); err != nil {
		return false
	}
	return c.rw.Flush() == nil
}
