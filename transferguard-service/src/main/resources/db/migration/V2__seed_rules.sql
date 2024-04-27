INSERT INTO rule (code, name, enabled, weight, params) VALUES
  ('R1_VELOCITY',              'Many transfers in a short window',          true, 40, '{"maxCount": 5, "window": "PT10M"}'),
  ('R2_NEW_PAYEE_HIGH_AMOUNT', 'Large first transfer to a new payee',       true, 25, '{"minAmount": "3000.00"}'),
  ('R3_AMOUNT_ANOMALY',        'Amount far above the 30-day average',       true, 25, '{"multiplier": 5, "minHistory": 3}'),
  ('R4_ODD_HOURS',             'Larger transfer at night',                  true, 10, '{"start": "00:00", "end": "06:00", "minAmount": "1000.00", "zone": "Asia/Kuala_Lumpur"}'),
  ('R5_PASS_THROUGH',          'Money received from many senders sent on',  true, 50, '{"window": "PT60M", "minSenders": 3, "outRatio": 0.80}'),
  ('R6_NEW_DEVICE',            'New device or password reset just before',  true, 20, '{"window": "PT30M"}'),
  ('R7_WATCHLIST',             'Payee is a reported mule account',          true, 70, '{}');
