-- The configuration file the real-Rex tests start Rex with (rex-real.yml, `--config`): a few
-- custom actions for RealRexActionsTest, which runs them from the phone's side and answers their
-- questions. Nothing else in Rex changes with it.

rex.action{
  name = "rexpocket_where",
  title = "RexPocket: Where Am I",
  description = "Says where the action ran",
  category = "RexPocket",
  keywords = "context probe",
  run = function(ctx, args)
    return { origin = ctx.origin, session = ctx.session_id, block = ctx.block_id }
  end,
}

rex.action{
  name = "rexpocket_ask",
  title = "RexPocket: Ask a Name",
  run = function(ctx, args)
    local name, why = rex.ui.input{ title = "Your name", placeholder = "name", value = "x", confirm = "Use", timeout = 60 }
    if name == nil then return { why = why } end
    return { name = name }
  end,
}

rex.action{
  name = "rexpocket_pick",
  title = "RexPocket: Pick a Fruit",
  run = function(ctx, args)
    local id, why = rex.ui.pick{
      title = "Fruit",
      items = { "Apple", { id = "b", title = "Banana", subtitle = "yellow", icon = "star", detail = "ripe" } },
      timeout = 60,
    }
    if id == nil then return { why = why } end
    return { id = id }
  end,
}

rex.action{
  name = "rexpocket_needs_args",
  title = "RexPocket: Needs a File",
  args = { file = "string" },
  run = function(ctx, args) return args end,
}
