package mysql

import (
	"context"

	"wechat-observatory/internal/bridge"
)

func (s *Store) ConfirmedReplyAcknowledgements(ctx context.Context, req bridge.ModuleAckRequest) ([]bridge.ModuleOutboxItem, error) {
	ids := make([]int64, 0, len(req.Items))
	for _, ack := range req.Items {
		if ack.ID > 0 {
			ids = append(ids, ack.ID)
		}
	}
	if len(ids) == 0 {
		return []bridge.ModuleOutboxItem{}, nil
	}
	items, err := s.listOutboxItemsForDevice(ctx, ids, req.Device)
	if err != nil {
		return nil, err
	}
	return bridge.MatchingReplyAcknowledgements(items, req), nil
}
