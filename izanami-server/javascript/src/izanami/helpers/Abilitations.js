
export function isAllowed(user, key, letter) {
    if (key && user && user.authorizedPatterns && letter) {
        return user.authorizedPatterns.map( ({pattern, rights}) => {
            const match = RegExp(`${pattern.replace("*", ".*")}`).test(key)
            return rights.includes(letter) && match;
        }).reduce((acc, elt) => acc || elt, false);
    } else {
        return true;
    }
}

export function isDeleteAllowed(user, key) {
    return isAllowed(user, key, "D");
}

export function isUpdateAllowed(user, key) {
    return isAllowed(user, key, "U");
}