CREATE INDEX idx_reservations_recent ON reservations(room_id,state,created_at,booking_id);
