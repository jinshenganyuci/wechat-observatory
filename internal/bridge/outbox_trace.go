package bridge

import "time"

type outboxTraceStage uint8

const (
	outboxTraceEnqueued outboxTraceStage = iota
	outboxTraceLeased
	outboxTraceWSWritten
	outboxTraceAckReceived
	outboxTraceAckCommitted
	outboxTraceAckReplayed
)

func (stage outboxTraceStage) name() string {
	switch stage {
	case outboxTraceEnqueued:
		return "enqueue_committed"
	case outboxTraceLeased:
		return "lease_committed"
	case outboxTraceWSWritten:
		return "ws_written"
	case outboxTraceAckReceived:
		return "ack_received"
	case outboxTraceAckCommitted:
		return "ack_committed"
	case outboxTraceAckReplayed:
		return "ack_replayed"
	default:
		return ""
	}
}

// Opt-in transport diagnostics accept only numeric task metadata and fixed
// stages, never a request, message body, recipient, credential or raw error.
func (s *Service) traceOutbox(stage outboxTraceStage, taskID int64, attempt int, elapsed time.Duration) {
	if !s.cfg.OutboxTrace || s.outboxTraceLog == nil || taskID <= 0 {
		return
	}
	name := stage.name()
	if name == "" {
		return
	}
	s.outboxTraceLog.Printf("outbox_trace task_id=%d stage=%s attempt=%d duration_ms=%d",
		taskID, name, attempt, max(int64(0), elapsed.Milliseconds()))
}
