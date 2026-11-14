def sound_name:
  (if type == "string" then . else (.name // "") end)
  | sub("^minecraft:"; "")
  | sub("^sounds/"; "")
  | sub("\\.ogg$"; "");
def entry_type:
  if type == "object" then (.type // "file") else "file" end;
def playable($files; $events):
  sound_name as $name
  | (entry_type == "file" and ($files[$name] // false))
    or (entry_type == "event" and ($events | has($name)));
def kept($files; $events):
  with_entries(
    . as $original
    | .value |= (
      if type == "object" and (.sounds | type) == "array" then
        .sounds = (.sounds | map(select(playable($files; $events))))
      else . end
    )
    # Vanilla intentionally registers silent events with an empty sound list.
    # Preserve those definitions (including subtitles); only discard an event
    # whose non-empty sample list became empty because its assets are absent.
    | select((.value.sounds | type) == "array"
      and ((.value.sounds | length) > 0 or ($original.value.sounds | length) == 0))
  );
def settle($source; $files; $events):
  ($source | kept($files; $events)) as $next
  | if ($next | length) == ($events | length) then $next
    else settle($source; $files; $next) end;
. as $source
| ($allowed[0] | map({key: ., value: true}) | from_entries) as $files
| settle($source; $files; {})
