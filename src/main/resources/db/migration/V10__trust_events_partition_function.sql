-- V10: B-008 (Event storage). Monthly partition creator for trust_events (Rule 6.8, Rulings 35, 43).
--
-- Partitions are keyed by received_at (platform-controlled), never occurred_at.
-- Bounds are written with an explicit +00 offset so the session time zone cannot shift them.
--
-- SECURITY DEFINER with a fixed search_path lets the B-026 partition job run as trustguard_app
-- without owning any table. The partition name is derived only from the validated date and is
-- quoted with %I. Every object reference is schema-qualified.
--
-- Accepted months: from the previous UTC month (inclusive) to the current UTC month + 12
-- (inclusive). Anything else is rejected (Ruling 43).
--
-- The body references public.trust_events, which V11 creates. plpgsql does not resolve table
-- names at creation time, so the forward reference is safe; V11 is the first caller.

CREATE OR REPLACE FUNCTION create_trust_events_partition(p_month_start date)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
SET lock_timeout = '5s'
AS $$
DECLARE
    v_current_month  date;
    v_earliest_month date;
    v_latest_month   date;
    v_partition_name text;
    v_lower_bound    text;
    v_upper_bound    text;
BEGIN
    IF p_month_start IS NULL OR EXTRACT(DAY FROM p_month_start) <> 1 THEN
        RAISE EXCEPTION 'p_month_start must be the first day of a month'
            USING ERRCODE = '22023';
    END IF;

    v_current_month  := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
    v_earliest_month := (v_current_month - interval '1 month')::date;
    v_latest_month   := (v_current_month + interval '12 months')::date;

    IF p_month_start < v_earliest_month THEN
        RAISE EXCEPTION 'p_month_start is earlier than the previous month'
            USING ERRCODE = '22023';
    END IF;
    IF p_month_start > v_latest_month THEN
        RAISE EXCEPTION 'p_month_start is more than 12 months ahead'
            USING ERRCODE = '22023';
    END IF;

    v_partition_name := 'trust_events_' || to_char(p_month_start, 'YYYY_MM');
    v_lower_bound := to_char(p_month_start, 'YYYY-MM-DD') || ' 00:00:00+00';
    v_upper_bound := to_char((p_month_start + interval '1 month')::date, 'YYYY-MM-DD') || ' 00:00:00+00';

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I PARTITION OF public.trust_events FOR VALUES FROM (%L) TO (%L)',
        v_partition_name, v_lower_bound, v_upper_bound);

    RETURN v_partition_name;
END;
$$;

-- PostgreSQL grants EXECUTE to PUBLIC by default; only the application role may call this.
REVOKE ALL ON FUNCTION create_trust_events_partition(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION create_trust_events_partition(date) TO trustguard_app;