package hydbridge

import (
	"strings"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/reflect/protoreflect"
)

// WhatsApp keeps adding message types, many of them a wrapper (FutureProofMessage) around an ordinary message, or a
// business message whose text sits in a field we don't list (e.g. login-code templates). These two fall-backs keep
// such messages readable instead of "unsupported".

// waWrappedMessage is the message inside the first FutureProofMessage wrapper set on msg, or nil.
func waWrappedMessage(msg *waE2E.Message) *waE2E.Message {
	var inner *waE2E.Message
	msg.ProtoReflect().Range(func(fd protoreflect.FieldDescriptor, v protoreflect.Value) bool {
		if fd.Kind() != protoreflect.MessageKind || fd.IsList() || fd.IsMap() {
			return true
		}
		if wrapper, ok := v.Message().Interface().(*waE2E.FutureProofMessage); ok && wrapper.GetMessage() != nil {
			inner = wrapper.GetMessage()
			return false
		}
		return true
	})
	return inner
}

// waTextFields are the field names that hold what a message says (not ids, urls, keys or quoted messages).
var waTextFields = map[protoreflect.Name]bool{
	"conversation": true, "text": true, "caption": true, "contentText": true, "footerText": true, "title": true,
	"description": true, "hydratedContentText": true, "hydratedTitleText": true, "hydratedFooterText": true,
}

// waSkippedFields hold other messages or metadata whose text isn't this message's (a quoted reply, link previews).
var waSkippedFields = map[protoreflect.Name]bool{"contextInfo": true, "messageContextInfo": true, "quotedMessage": true}

// waReadableText collects the text fields of a message type describeWAMessage doesn't know, in field order, without
// repeats; empty if there are none.
func waReadableText(msg *waE2E.Message) string {
	var parts []string
	seen := map[string]bool{}
	var walk func(m protoreflect.Message, depth int)
	walk = func(m protoreflect.Message, depth int) {
		if depth > waMaxTextDepth {
			return
		}
		m.Range(func(fd protoreflect.FieldDescriptor, v protoreflect.Value) bool {
			switch {
			case waSkippedFields[fd.Name()] || fd.IsMap():
			case fd.Kind() == protoreflect.StringKind && !fd.IsList() && waTextFields[fd.Name()]:
				if s := strings.TrimSpace(v.String()); s != "" && !seen[s] {
					seen[s] = true
					parts = append(parts, s)
				}
			case fd.Kind() == protoreflect.MessageKind && !fd.IsList():
				walk(v.Message(), depth+1)
			}
			return true
		})
	}
	walk(msg.ProtoReflect(), 0)
	return strings.Join(parts, "\n")
}

const waMaxTextDepth = 6

// waSetFields names the top-level fields set on msg, to log message types we can't show yet.
func waSetFields(msg *waE2E.Message) []string {
	var names []string
	msg.ProtoReflect().Range(func(fd protoreflect.FieldDescriptor, _ protoreflect.Value) bool {
		names = append(names, string(fd.Name()))
		return true
	})
	return names
}
