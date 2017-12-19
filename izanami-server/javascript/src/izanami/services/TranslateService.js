const translateObject = {
  obj: {
    id: {
      error: {
        pattern: "Invalid pattern for field Id"
      }
    },
    clientId: {
      error: {
        pattern: "Invalid pattern for field clientId"
      }
    },
    parameters: {
      ref: {
        error: {
          path: {
            missing: "Invalid path"
          }
        }
      }
    }
  },
  error: {
    data: {
      exists: "The data with id {0} already exist",
      missing: "Data is missing"
    },
    forbidden: "Forbidden action : Invalid authorized pattern"
  },
  auth: {
    invalid: {
      login : "Invalid login or password"
    }
  },
  file: {
    import: {
      success: '{0} lines correctly imported'
    }
  }
};

export function translate({message, args = []}) {
  console.debug("Hey Jude, can you translate this key for me please? ", message);
  const patternMessage = findProp(translateObject, message) || '';
  return args.reduce( (acc, arg, i) => acc.replace(`{${i}}`, arg), patternMessage);
}

function findProp(obj, prop, defval) {
  if (typeof defval === 'undefined') defval = null;
  prop = prop.split('.');
  for (var i = 0; i < prop.length; i++) {
    if (typeof obj[prop[i]] === 'undefined')
      return defval;
    obj = obj[prop[i]];
  }
  return obj;
}