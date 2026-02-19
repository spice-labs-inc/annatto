#!/usr/bin/env elixir
# Extracts metadata from Hex .tar packages and produces Annatto JSON schema.
# Usage: elixir extract.exs <tarball_or_dir> [output_dir]
#
# If given a directory, processes all .tar files in it (batch mode).
# If given a single file, processes just that file.
# Output is written to output_dir/<name>-<version>.json

defmodule JsonEncoder do
  @moduledoc "Minimal JSON encoder for Annatto schema output."

  def encode(value), do: do_encode(value)

  defp do_encode(nil), do: "null"
  defp do_encode(true), do: "true"
  defp do_encode(false), do: "false"
  defp do_encode(n) when is_integer(n), do: Integer.to_string(n)
  defp do_encode(s) when is_binary(s), do: "\"" <> escape_string(s) <> "\""
  defp do_encode(list) when is_list(list) do
    items = Enum.map(list, &do_encode/1) |> Enum.join(", ")
    "[" <> items <> "]"
  end
  defp do_encode(map) when is_map(map) do
    items = Enum.map(map, fn {k, v} ->
      do_encode(to_string(k)) <> ": " <> do_encode(v)
    end) |> Enum.join(", ")
    "{" <> items <> "}"
  end

  defp escape_string(s) do
    s
    |> String.replace("\\", "\\\\")
    |> String.replace("\"", "\\\"")
    |> String.replace("\n", "\\n")
    |> String.replace("\r", "\\r")
    |> String.replace("\t", "\\t")
  end
end

defmodule HexExtractor do
  def extract(tarball_path) do
    {:ok, files} = :erl_tar.extract(String.to_charlist(tarball_path), [:memory])

    metadata_content = Enum.find_value(files, fn
      {~c"metadata.config", content} -> content
      _ -> nil
    end)

    if metadata_content == nil do
      {:error, "no metadata.config found"}
    else
      terms = consult_string(metadata_content)
      meta = Map.new(terms)

      name = get_binary(meta, "name")
      version = get_binary(meta, "version")
      description = get_binary(meta, "description")
      licenses = get_list_of_binaries(meta, "licenses")
      requirements = get_requirements(meta)

      license = case licenses do
        [] -> nil
        list -> Enum.join(list, " OR ")
      end

      deps = normalize_requirements(requirements)
        |> Enum.sort_by(& &1["name"])

      json = %{
        "name" => name,
        "simpleName" => name,
        "version" => version,
        "description" => description,
        "license" => license,
        "publisher" => nil,
        "publishedAt" => nil,
        "dependencies" => deps
      }

      {:ok, name, version, json}
    end
  end

  defp get_binary(meta, key) do
    case Map.get(meta, key) do
      val when is_binary(val) -> val
      _ -> nil
    end
  end

  defp get_list_of_binaries(meta, key) do
    case Map.get(meta, key) do
      list when is_list(list) -> Enum.filter(list, &is_binary/1)
      _ -> []
    end
  end

  defp get_requirements(meta) do
    case Map.get(meta, "requirements") do
      list when is_list(list) -> list
      _ -> []
    end
  end

  # Normalize requirements from both Elixir (mix) and Erlang (rebar3) formats.
  # Mix format: [[{<<"name">>, <<"dep">>}, {<<"requirement">>, <<"~> 1.0">>}, ...]]
  # Rebar3 format: [{<<"dep_name">>, [{<<"requirement">>, <<"~> 1.0">>}, ...]}]
  defp normalize_requirements(reqs) do
    Enum.map(reqs, fn
      # Rebar3 format: {name_binary, proplist_of_attrs}
      {name, attrs} when is_binary(name) and is_list(attrs) ->
        requirement = get_from_req(attrs, "requirement")
        %{"name" => name, "versionConstraint" => requirement, "scope" => "runtime"}

      # Mix format: proplist or map with "name" key
      req ->
        dep_name = get_from_req(req, "name")
        requirement = get_from_req(req, "requirement")
        %{"name" => dep_name, "versionConstraint" => requirement, "scope" => "runtime"}
    end)
  end

  # Parse Erlang terms from a string (compatible with OTP < 27 which lacks :file.consult_string)
  defp consult_string(content) when is_binary(content) do
    consult_string(String.to_charlist(content))
  end
  defp consult_string(content) when is_list(content) do
    {:ok, tokens, _} = :erl_scan.string(content)
    parse_terms(tokens, [])
  end

  defp parse_terms([], acc), do: Enum.reverse(acc)
  defp parse_terms(tokens, acc) do
    {term_tokens, rest} = Enum.split_while(tokens, fn {type, _, _} -> type != :dot; {type, _} -> type != :dot end)
    # rest starts with dot token, skip it
    rest = case rest do
      [{:dot, _} | r] -> r
      [{:dot, _, _} | r] -> r
      [] -> []
    end
    {:ok, term} = :erl_parse.parse_term(term_tokens ++ [{:dot, 1}])
    parse_terms(rest, [term | acc])
  end

  # Requirements can be a proplist [{key, val}] or a map
  defp get_from_req(req, key) when is_map(req), do: Map.get(req, key)
  defp get_from_req(req, key) when is_list(req) do
    case List.keyfind(req, key, 0) do
      {^key, val} -> val
      _ -> nil
    end
  end
  defp get_from_req(_, _), do: nil
end

# Use jq for pretty-printing since we don't have Jason
defmodule PrettyJson do
  def format(json_str) do
    port = Port.open({:spawn, "jq ."}, [:binary, :exit_status, {:line, 65536}])
    Port.command(port, json_str)
    Port.command(port, "\n")
    Port.close(port)
    # Give jq a moment and collect output
    collect_port_output("")
  end

  defp collect_port_output(acc) do
    receive do
      {_port, {:data, {:eol, line}}} -> collect_port_output(acc <> line <> "\n")
      {_port, {:exit_status, 0}} -> acc
      {_port, {:exit_status, _}} -> acc
    after
      500 -> acc
    end
  end
end

[input | rest] = System.argv()
output_dir = case rest do
  [dir | _] -> dir
  [] -> "out"
end

File.mkdir_p!(output_dir)

files = if File.dir?(input) do
  Path.wildcard(Path.join(input, "*.tar"))
  |> Enum.sort()
else
  [input]
end

for file <- files do
  basename = Path.basename(file)
  IO.write("Processing #{basename}... ")

  case HexExtractor.extract(file) do
    {:ok, name, version, json} ->
      out_file = Path.join(output_dir, "#{name}-#{version}.json")
      compact_json = JsonEncoder.encode(json)
      # Write compact JSON to temp file, pipe through jq for pretty-printing
      tmp_file = Path.join(output_dir, ".tmp_#{name}.json")
      File.write!(tmp_file, compact_json)
      {pretty, 0} = System.cmd("jq", ["--sort-keys", ".", tmp_file])
      File.rm!(tmp_file)
      File.write!(out_file, pretty)
      IO.puts("OK -> #{Path.basename(out_file)}")

    {:error, reason} ->
      IO.puts("FAIL: #{reason}")
  end
end
