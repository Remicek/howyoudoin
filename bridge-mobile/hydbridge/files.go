package hydbridge

import (
	"fmt"
	"math/rand/v2"
	"os"
)

// partPath is a temporary name next to path for one writer. Downloads go there and are renamed into place when
// complete, so a file at path is always whole: a second download of the same media (e.g. our own sent photo, seen
// both as the send's echo and as the network's update) never shows a half-written file.
func partPath(path string) string {
	return fmt.Sprintf("%s.part-%d", path, rand.Int64())
}

// writeFileAtomic writes data to path so that it appears all at once.
func writeFileAtomic(path string, data []byte) error {
	tmp := partPath(path)
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}
