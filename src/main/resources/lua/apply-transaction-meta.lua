local function binlog_sequence(filename)
  local digits = string.match(filename or '', '(%d+)$')
  if digits == nil then
    return -1
  end
  return tonumber(digits)
end

local function is_newer(current_version, incoming_version)
  local current_file = binlog_sequence(current_version.binlogFilename)
  local incoming_file = binlog_sequence(incoming_version.binlogFilename)
  if incoming_file ~= current_file then
    return incoming_file > current_file
  end
  if incoming_version.nextPosition ~= current_version.nextPosition then
    return incoming_version.nextPosition > current_version.nextPosition
  end
  return incoming_version.eventIndex > current_version.eventIndex
end

if redis.call('EXISTS', KEYS[1]) == 1 then
  return 'DUPLICATE'
end

local argIndex = 1
for keyIndex = 2, #KEYS, 2 do
  local businessKey = KEYS[keyIndex]
  local metadataKey = KEYS[keyIndex + 1]
  local eventType = ARGV[argIndex]
  local payload = ARGV[argIndex + 1]
  local incomingMetadataJson = ARGV[argIndex + 2]
  local incomingMetadata = cjson.decode(incomingMetadataJson)
  local storedMetadataJson = redis.call('GET', metadataKey)

  local shouldApply = true
  if storedMetadataJson then
    local storedMetadata = cjson.decode(storedMetadataJson)
    shouldApply = is_newer(storedMetadata.version, incomingMetadata.version)
  end

  if shouldApply then
    if eventType == 'DELETE' then
      redis.call('DEL', businessKey)
    elseif eventType == 'INSERT' or eventType == 'UPDATE' then
      redis.call('SET', businessKey, payload)
    else
      return redis.error_reply('Unexpected CDC transaction eventType: ' .. tostring(eventType))
    end
    redis.call('SET', metadataKey, incomingMetadataJson)
  end

  argIndex = argIndex + 3
end

redis.call('SET', KEYS[1], ARGV[#ARGV])
return 'APPLIED'
