if redis.call('EXISTS', KEYS[1]) == 1 then
  return 'DUPLICATE'
end
local argIndex = 1
for i = 2, #KEYS do
  local operation = ARGV[argIndex]
  if operation == 'SET' then
    redis.call('SET', KEYS[i], ARGV[argIndex + 1])
    argIndex = argIndex + 2
  elseif operation == 'DEL' then
    redis.call('DEL', KEYS[i])
    argIndex = argIndex + 1
  else
    return redis.error_reply('Unexpected Redis transaction operation: ' .. tostring(operation))
  end
end
redis.call('SET', KEYS[1], ARGV[#ARGV])
return 'APPLIED'
