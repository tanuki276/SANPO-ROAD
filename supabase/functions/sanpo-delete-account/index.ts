import { createClient } from "npm:@supabase/supabase-js@2.49.8";

Deno.serve(async (request: Request) => {
  const reply = (status: number, error?: string) => Response.json(error ? { error } : { deleted: true }, { status, headers: { "Cache-Control": "no-store" } });
  if (request.method !== "POST") return reply(405, "method_not_allowed");
  const bearer = request.headers.get("authorization") || "";
  if (!bearer.startsWith("Bearer ")) return reply(401, "unauthorized");
  try {
    const body = await request.json();
    if (body.confirm !== "DELETE") return reply(400, "confirmation_required");
    const url = Deno.env.get("SUPABASE_URL")!;
    const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false, autoRefreshToken: false } });
    const caller = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, { global: { headers: { Authorization: bearer } }, auth: { persistSession: false, autoRefreshToken: false } });
    const token = bearer.slice(7);
    const { data: { user }, error } = await admin.auth.getUser(token);
    if (error || !user || user.is_anonymous) return reply(401, "unauthorized");
    const { data: allowed, error: policyError } = await caller.rpc("sanpo_can_delete_account_v1");
    if (policyError) return reply(503, "account_check_failed");
    if (!allowed) return reply(409, "account_used_by_another_app");
    const { error: logoutError } = await admin.auth.admin.signOut(token, "global");
    if (logoutError) throw logoutError;
    // Delete through Storage API, never delete storage.objects rows directly.
    while (true) {
      const { data: objects, error: listError } = await admin.storage.from("walk-photos").list(user.id, { limit: 100 });
      if (listError) throw listError;
      if (!objects?.length) break;
      const { error: removeError } = await admin.storage.from("walk-photos").remove(objects.map(o => `${user.id}/${o.name}`));
      if (removeError) throw removeError;
    }
    for (const [table, field] of [["sanpo_shares", "owner_id"], ["sanpo_routes", "user_id"]]) {
      const { error: deleteError } = await admin.from(table).delete().eq(field, user.id);
      if (deleteError) throw deleteError;
    }
    const { error: deleteError } = await admin.auth.admin.deleteUser(user.id);
    if (deleteError) throw deleteError;
    return reply(200);
  } catch {
    return reply(503, "delete_failed_retry_after_login");
  }
});
