package bridge

import "context"

// OutboxAckReader confirms a retry of an already committed result without
// transitioning the task again or publishing another outbound message event.
// The service invokes it under the same current-account lock as normal ACKs.
type OutboxAckReader interface {
	ConfirmedReplyAcknowledgements(context.Context, ModuleAckRequest) ([]ModuleOutboxItem, error)
}

// MatchingReplyAcknowledgements excludes unknown, pending/cancelled, other
// owner/device and conflicting results. In particular, a repeated "sent" ACK
// cannot overwrite a failed/cancelled result or claim a different chat record.
func MatchingReplyAcknowledgements(items []ModuleOutboxItem, req ModuleAckRequest) []ModuleOutboxItem {
	acks := make(map[int64]ModuleAckItem, len(req.Items))
	for _, ack := range req.Items {
		if ack.ID > 0 {
			acks[ack.ID] = ack
		}
	}
	confirmed := make([]ModuleOutboxItem, 0, len(items))
	for _, item := range items {
		ack, ok := acks[item.ID]
		if !ok || item.Device != req.Device || (req.WxID != "" && item.OwnerWxID != req.WxID) ||
			(item.Status != "sent" && item.Status != "failed") || item.Status != ack.Status ||
			item.ChatRecordID != ack.ChatRecordID || item.LastError != ack.Error {
			continue
		}
		confirmed = append(confirmed, item)
		delete(acks, item.ID)
	}
	return confirmed
}
