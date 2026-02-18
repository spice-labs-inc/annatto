-- Extracts metadata from a .rockspec file
local rockspec_file = arg[1]
local env = {}
setmetatable(env, {__index = function() return function() end end})
local chunk, err = loadfile(rockspec_file, "t", env)
if not chunk then
    io.stderr:write("Error loading rockspec: " .. tostring(err) .. "\n")
    os.exit(1)
end
chunk()
local json_encode -- simple JSON output
json_encode = function(val)
    if type(val) == "string" then return '"' .. val:gsub('"', '\\"') .. '"'
    elseif type(val) == "number" then return tostring(val)
    elseif type(val) == "boolean" then return tostring(val)
    elseif type(val) == "table" then
        local parts = {}
        local is_array = #val > 0
        for k, v in pairs(val) do
            if is_array then table.insert(parts, json_encode(v))
            else table.insert(parts, '"' .. tostring(k) .. '": ' .. json_encode(v)) end
        end
        return is_array and "[" .. table.concat(parts, ", ") .. "]" or "{" .. table.concat(parts, ", ") .. "}"
    else return "null" end
end
print(json_encode({
    package = env.package,
    version = env.version,
    description = env.description,
    dependencies = env.dependencies,
    external_dependencies = env.external_dependencies
}))
