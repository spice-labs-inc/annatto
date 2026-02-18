# Extracts metadata from a Hex .tar package (metadata.config in Erlang term format)
[tarball | _] = System.argv()
{:ok, files} = :erl_tar.extract(String.to_charlist(tarball), [:memory])
metadata = Enum.find_value(files, fn
  {~c"metadata.config", content} -> content
  _ -> nil
end)
if metadata do
  {:ok, terms} = :file.consult_string(metadata)
  IO.puts(inspect(terms, pretty: true, limit: :infinity))
else
  IO.puts(~s({"error": "no metadata.config found"}))
end
