#!/usr/bin/env lua5.4
-- Source-of-truth metadata extractor for LuaRocks packages.
-- Produces Annatto-schema JSON from .src.rock or .rockspec files.
--
-- Usage:
--   lua5.4 extract.lua <file>           -- extract single file
--   lua5.4 extract.lua --batch <dir>    -- extract all packages in dir

-- JSON encoder that handles the Annatto schema requirements:
-- deterministic key ordering, proper null/string/number/boolean encoding
local json_encode
json_encode = function(val, indent)
    indent = indent or ""
    local next_indent = indent .. "  "
    if val == nil then
        return "null"
    elseif type(val) == "string" then
        -- Escape special chars
        local escaped = val:gsub('\\', '\\\\')
                           :gsub('"', '\\"')
                           :gsub('\n', '\\n')
                           :gsub('\r', '\\r')
                           :gsub('\t', '\\t')
                           :gsub('[\x00-\x1f]', function(c)
                               return string.format('\\u%04x', string.byte(c))
                           end)
        return '"' .. escaped .. '"'
    elseif type(val) == "number" then
        if val == math.floor(val) and val >= -2^53 and val <= 2^53 then
            return string.format("%d", val)
        end
        return tostring(val)
    elseif type(val) == "boolean" then
        return tostring(val)
    elseif type(val) == "table" then
        -- Check if it's an array (sequential integer keys starting at 1)
        local is_array = true
        local max_n = 0
        for k, _ in pairs(val) do
            if type(k) ~= "number" or k ~= math.floor(k) or k < 1 then
                is_array = false
                break
            end
            if k > max_n then max_n = k end
        end
        if max_n == 0 then
            -- Check if table is truly empty or has string keys
            for _ in pairs(val) do
                is_array = false
                break
            end
        end
        if is_array and max_n > 0 then
            -- Verify contiguous
            for i = 1, max_n do
                if val[i] == nil then
                    is_array = false
                    break
                end
            end
        end

        if is_array and max_n > 0 then
            local parts = {}
            for i = 1, max_n do
                table.insert(parts, next_indent .. json_encode(val[i], next_indent))
            end
            return "[\n" .. table.concat(parts, ",\n") .. "\n" .. indent .. "]"
        else
            -- Object with sorted keys
            local keys = {}
            for k in pairs(val) do
                table.insert(keys, tostring(k))
            end
            table.sort(keys)
            if #keys == 0 then
                return "{}"
            end
            local parts = {}
            for _, k in ipairs(keys) do
                table.insert(parts, next_indent .. json_encode(k) .. ": " .. json_encode(val[k], next_indent))
            end
            return "{\n" .. table.concat(parts, ",\n") .. "\n" .. indent .. "}"
        end
    end
    return "null"
end

-- Parse a LuaRocks dependency string into name and version constraint
-- Format: "name [op version [, op version ...]]"
local function parse_dependency(dep_str)
    if type(dep_str) ~= "string" then return nil end
    local trimmed = dep_str:match("^%s*(.-)%s*$")
    if trimmed == "" then return nil end

    -- Split on first space
    local name, rest = trimmed:match("^(%S+)%s+(.+)$")
    if not name then
        -- Name only, no version constraint
        return {name = trimmed, versionConstraint = nil}
    end
    return {name = name, versionConstraint = rest}
end

-- Load and evaluate a rockspec file in a sandboxed environment
local function load_rockspec(filepath)
    local env = {}
    -- Sandbox: allow basic operations but no dangerous ones
    setmetatable(env, {
        __index = function(_, key)
            -- Allow access to common globals used in rockspecs
            if key == "pairs" then return pairs
            elseif key == "ipairs" then return ipairs
            elseif key == "type" then return type
            elseif key == "tostring" then return tostring
            elseif key == "tonumber" then return tonumber
            elseif key == "string" then return string
            elseif key == "table" then return table
            elseif key == "math" then return math
            elseif key == "print" then return function() end -- no-op
            elseif key == "require" then return function() return {} end -- stub
            elseif key == "pcall" then return pcall
            elseif key == "error" then return error
            elseif key == "select" then return select
            elseif key == "unpack" then return table.unpack
            end
            return nil
        end
    })

    local chunk, err = loadfile(filepath, "t", env)
    if not chunk then
        io.stderr:write("Error loading rockspec: " .. tostring(err) .. "\n")
        return nil
    end

    local ok, run_err = pcall(chunk)
    if not ok then
        io.stderr:write("Error executing rockspec: " .. tostring(run_err) .. "\n")
        return nil
    end

    return env
end

-- Extract a rockspec from a .src.rock (ZIP) file
local function find_rockspec_in_rock(rock_path)
    -- Unzip to temp dir, find .rockspec file
    local tmpdir = os.tmpname() .. "_rock"
    os.execute("mkdir -p " .. tmpdir)
    local cmd = string.format("unzip -q -o %q -d %q 2>/dev/null", rock_path, tmpdir)
    os.execute(cmd)

    -- Find .rockspec at root level
    local handle = io.popen(string.format("find %q -maxdepth 1 -name '*.rockspec' -type f 2>/dev/null", tmpdir))
    local rockspec_path = nil
    if handle then
        rockspec_path = handle:read("*l")
        handle:close()
    end

    if not rockspec_path or rockspec_path == "" then
        -- Try one level deeper
        handle = io.popen(string.format("find %q -maxdepth 2 -name '*.rockspec' -type f 2>/dev/null", tmpdir))
        if handle then
            rockspec_path = handle:read("*l")
            handle:close()
        end
    end

    return rockspec_path, tmpdir
end

-- Build Annatto JSON string from a rockspec environment.
-- We produce the JSON directly to control field order and explicit null output.
local function build_output(env)
    if not env then
        return nil
    end

    local pkg_name = env.package
    local pkg_version = env.version

    -- Description handling: summary preferred, detailed fallback
    local description_text = nil
    local license_text = nil
    local publisher_text = nil

    if type(env.description) == "table" then
        if type(env.description.summary) == "string" and env.description.summary ~= "" then
            description_text = env.description.summary
        elseif type(env.description.detailed) == "string" and env.description.detailed ~= "" then
            description_text = env.description.detailed
        end
        if type(env.description.license) == "string" and env.description.license ~= "" then
            license_text = env.description.license
        end
        if type(env.description.maintainer) == "string" and env.description.maintainer ~= "" then
            publisher_text = env.description.maintainer
        end
    elseif type(env.description) == "string" then
        description_text = env.description
    end

    -- Dependencies: dependencies -> runtime, build_dependencies -> build, test_dependencies -> test
    -- external_dependencies are filtered out entirely
    local deps = {}

    local function add_deps(dep_table, scope)
        if type(dep_table) ~= "table" then return end
        for _, dep_str in ipairs(dep_table) do
            local parsed = parse_dependency(dep_str)
            if parsed then
                table.insert(deps, {
                    name = parsed.name,
                    versionConstraint = parsed.versionConstraint,
                    scope = scope
                })
            end
        end
    end

    add_deps(env.dependencies, "runtime")
    add_deps(env.build_dependencies, "build")
    add_deps(env.test_dependencies, "test")

    -- Build JSON string with explicit field ordering and null fields
    local lines = {}
    table.insert(lines, "{")
    table.insert(lines, "  \"name\": " .. json_encode(pkg_name) .. ",")
    table.insert(lines, "  \"simpleName\": " .. json_encode(pkg_name) .. ",")
    table.insert(lines, "  \"version\": " .. json_encode(pkg_version) .. ",")
    table.insert(lines, "  \"description\": " .. json_encode(description_text) .. ",")
    table.insert(lines, "  \"license\": " .. json_encode(license_text) .. ",")
    table.insert(lines, "  \"publisher\": " .. json_encode(publisher_text) .. ",")
    table.insert(lines, "  \"publishedAt\": null,")

    -- Dependencies array
    if #deps == 0 then
        table.insert(lines, "  \"dependencies\": []")
    else
        table.insert(lines, "  \"dependencies\": [")
        for i, dep in ipairs(deps) do
            local dep_parts = {}
            table.insert(dep_parts, "    \"name\": " .. json_encode(dep.name))
            if dep.versionConstraint ~= nil then
                table.insert(dep_parts, "    \"versionConstraint\": " .. json_encode(dep.versionConstraint))
            else
                table.insert(dep_parts, "    \"versionConstraint\": null")
            end
            table.insert(dep_parts, "    \"scope\": " .. json_encode(dep.scope))
            local sep = (i < #deps) and "," or ""
            table.insert(lines, "    {")
            table.insert(lines, table.concat(dep_parts, ",\n"))
            table.insert(lines, "    }" .. sep)
        end
        table.insert(lines, "  ]")
    end

    table.insert(lines, "}")
    return table.concat(lines, "\n")
end

-- Main entry point
local function main()
    if #arg < 1 then
        io.stderr:write("Usage: extract.lua [--batch <dir> | <file>]\n")
        os.exit(1)
    end

    if arg[1] == "--batch" then
        -- Batch mode: process all packages in directory
        local dir = arg[2] or "/work/out"
        local outdir = arg[3] or "/work/expected"
        os.execute("mkdir -p " .. outdir)

        local handle = io.popen(string.format(
            "find %q -maxdepth 1 \\( -name '*.src.rock' -o -name '*.all.rock' -o -name '*.rockspec' \\) -type f | sort",
            dir))
        if not handle then
            io.stderr:write("Failed to list files in " .. dir .. "\n")
            os.exit(1)
        end

        for filepath in handle:lines() do
            local filename = filepath:match("([^/]+)$")
            io.stderr:write("Processing: " .. filename .. "\n")

            local rockspec_path = filepath
            local tmpdir = nil

            if filename:match("%.rock$") then
                rockspec_path, tmpdir = find_rockspec_in_rock(filepath)
                if not rockspec_path then
                    io.stderr:write("  SKIP: no rockspec found in " .. filename .. "\n")
                    goto continue
                end
            end

            local env = load_rockspec(rockspec_path)
            local result = build_output(env)

            if result then
                -- Strip extension for output filename
                local basename
                if filename:match("%.src%.rock$") then
                    basename = filename:gsub("%.src%.rock$", "")
                elseif filename:match("%.all%.rock$") then
                    basename = filename:gsub("%.all%.rock$", "")
                else
                    basename = filename:gsub("%.[^.]+$", "")
                end

                local outpath = outdir .. "/" .. basename .. "-expected.json"
                local outfile = io.open(outpath, "w")
                if outfile then
                    outfile:write(result .. "\n")
                    outfile:close()
                    io.stderr:write("  OK: " .. outpath .. "\n")
                else
                    io.stderr:write("  FAIL: cannot write " .. outpath .. "\n")
                end
            else
                io.stderr:write("  FAIL: could not extract metadata from " .. filename .. "\n")
            end

            if tmpdir then
                os.execute("rm -rf " .. tmpdir)
            end

            ::continue::
        end
        handle:close()
    else
        -- Single file mode
        local filepath = arg[1]
        local filename = filepath:match("([^/]+)$") or filepath

        local rockspec_path = filepath
        local tmpdir = nil

        if filename:match("%.rock$") then
            rockspec_path, tmpdir = find_rockspec_in_rock(filepath)
            if not rockspec_path then
                io.stderr:write("No rockspec found in " .. filename .. "\n")
                os.exit(1)
            end
        end

        local env = load_rockspec(rockspec_path)
        local result = build_output(env)

        if tmpdir then
            os.execute("rm -rf " .. tmpdir)
        end

        if result then
            print(result)
        else
            io.stderr:write("Failed to extract metadata\n")
            os.exit(1)
        end
    end
end

main()
