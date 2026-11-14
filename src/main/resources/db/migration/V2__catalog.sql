-- Demo catalog. Prices are created in Stripe by the catalog sync (idempotent, matched by lookup_key).
insert into plans (code, name, tier, trial_days, features) values
  ('free',    'Free',    0, 0,  '{"projects": 1,  "seats": 1,  "api_calls_per_month": 1000,    "priority_support": false, "audit_log": false, "sso": false}'),
  ('starter', 'Starter', 1, 14, '{"projects": 5,  "seats": 3,  "api_calls_per_month": 20000,   "priority_support": false, "audit_log": false, "sso": false}'),
  ('pro',     'Pro',     2, 14, '{"projects": 50, "seats": 10, "api_calls_per_month": 250000,  "priority_support": true,  "audit_log": true,  "sso": false}'),
  ('team',    'Team',    3, 0,  '{"projects": -1, "seats": 50, "api_calls_per_month": 2000000, "priority_support": true,  "audit_log": true,  "sso": true}');

insert into plan_prices (lookup_key, plan_code, billing_interval, unit_amount) values
  ('starter_monthly', 'starter', 'month', 900),
  ('starter_yearly',  'starter', 'year',  9000),
  ('pro_monthly',     'pro',     'month', 2900),
  ('pro_yearly',      'pro',     'year',  29000),
  ('team_monthly',    'team',    'month', 7900),
  ('team_yearly',     'team',    'year',  79000);
