package handlers

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"go.uber.org/zap"
)

//nolint:gosec
const token_resp string = `
{ 
  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
  "token_type": "Bearer",
  "expires_in": 3600,
}`

const by_email_bob_resp = `[
{"id": "bobid", "username":"bob.smith"}
]
`
const by_email_alice_resp = `[
{"id": "aliceid", "username":"alice.smith"}
]
`
const by_username_bob_resp = `[
{"id": "bobid", "username":"bob.smith"}
]`

const group_submember_resp = `[
	{"id": "bobid", "username":"bob.smith"},
	{"id": "aliceid", "username":"alice.smith"}
]`
const group_resp = `{
	"id": "group1-uuid",
	"name": "group1"
}`

func test_keycloakConfig(server *httptest.Server) KeyCloakConfg {
	return KeyCloakConfg{
		Url:            server.URL,
		ClientId:       "c1",
		ClientSecret:   "cs",
		Realm:          "tdf",
		LegacyKeycloak: false,
	}
}

func test_server_resp(t *testing.T, w http.ResponseWriter, r *http.Request, k string, reqRespMap map[string]string) {
	i, ok := reqRespMap[k]
	if ok == true {
		w.Header().Set("Content-Type", "application/json")
		_, err := io.WriteString(w, i)
		if err != nil {
			t.Error(err)
		}
	} else {
		t.Errorf("UnExpected Request, got: %s", r.URL.Path)
	}
}
func test_server(t *testing.T, userSearchQueryAndResp map[string]string, groupSearchQueryAndResp map[string]string,
	groupByIdAndResponse map[string]string, groupMemberQueryAndResponse map[string]string) *httptest.Server {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/realms/tdf/protocol/openid-connect/token" {
			_, err := io.WriteString(w, token_resp)
			if err != nil {
				t.Error(err)
			}
		} else if r.URL.Path == "/admin/realms/tdf/users" {
			test_server_resp(t, w, r, r.URL.RawQuery, userSearchQueryAndResp)
		} else if r.URL.Path == "/admin/realms/tdf/groups" && groupSearchQueryAndResp != nil {
			test_server_resp(t, w, r, r.URL.RawQuery, groupSearchQueryAndResp)
		} else if strings.HasPrefix(r.URL.Path, "/admin/realms/tdf/groups") &&
			strings.HasSuffix(r.URL.Path, "members") && groupMemberQueryAndResponse != nil {
			groupId := r.URL.Path[len("/admin/realms/tdf/groups/"):strings.LastIndex(r.URL.Path, "/")]
			test_server_resp(t, w, r, groupId, groupMemberQueryAndResponse)
		} else if strings.HasPrefix(r.URL.Path, "/admin/realms/tdf/groups") && groupByIdAndResponse != nil {
			groupId := r.URL.Path[strings.LastIndex(r.URL.Path, "/")+1:]
			test_server_resp(t, w, r, groupId, groupByIdAndResponse)
		} else {
			t.Errorf("UnExpected Request, got: %s", r.URL.Path)
		}
	}))
	return server
}

func Test_BadRequest_Get(t *testing.T) {

	zapLog, _ := zap.NewDevelopment()

	server := test_server(t, map[string]string{}, nil, nil, nil)
	defer server.Close()

	validBody := `{"entity_identifiers": [{"identifier": "bob@sample.org", "type": "email"}]}`
	testReq := httptest.NewRequest(http.MethodGet, "http://test",
		strings.NewReader(validBody))
	handler := GetEntityResolutionHandler(test_keycloakConfig(server), zapLog.Sugar())
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, testReq)

	resp := w.Result()
	defer resp.Body.Close()

	assert.Equal(t, http.StatusMethodNotAllowed, resp.StatusCode)
}

func Test_BadRequestPost(t *testing.T) {

	zapLog, _ := zap.NewDevelopment()

	server := test_server(t, map[string]string{}, nil, nil, nil)
	defer server.Close()

	// invalid type
	badBody := `{"entity_identifiers": [{"identifier": "bob@sample.org", "type": "somebadtype"}]}`
	testReq := httptest.NewRequest(http.MethodPost, "http://test",
		strings.NewReader(badBody))
	handler := GetEntityResolutionHandler(test_keycloakConfig(server), zapLog.Sugar())
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, testReq)

	resp := w.Result()
	defer resp.Body.Close()
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)

	// no type
	badBody = `{"entity_identifiers": [{"identifier": "bob@sample.org"}]}`
	testReq2 := httptest.NewRequest(http.MethodPost, "http://test",
		strings.NewReader(badBody))
	handler2 := GetEntityResolutionHandler(test_keycloakConfig(server), zapLog.Sugar())
	w2 := httptest.NewRecorder()
	handler2.ServeHTTP(w2, testReq2)

	resp2 := w2.Result()
	defer resp2.Body.Close()
	assert.Equal(t, http.StatusBadRequest, resp2.StatusCode)
}

func Test_ByEmail(t *testing.T) {
	zapLog, _ := zap.NewDevelopment()

	server := test_server(t, map[string]string{
		"email=bob%40sample.org":   by_email_bob_resp,
		"email=alice%40sample.org": by_email_alice_resp,
	}, nil, nil, nil)
	defer server.Close()

	validBody := `{"entity_identifiers": [{"identifier": "bob@sample.org", "type": "email"},{"identifier": "alice@sample.org", "type": "email"}]}`
	testReq := httptest.NewRequest(http.MethodPost, "http://test",
		strings.NewReader(validBody))
	handler := GetEntityResolutionHandler(test_keycloakConfig(server), zapLog.Sugar())
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, testReq)

	resp := w.Result()
	defer resp.Body.Close()

	assert.Equal(t, http.StatusOK, resp.StatusCode)

	var deserializedResp []EntityResolution
	body, err := io.ReadAll(resp.Body)
	assert.Nil(t, err)
	err = json.Unmarshal(body, &deserializedResp)
	assert.Nil(t, err)
	assert.Equal(t, 2, len(deserializedResp))
	assert.Equal(t, "bob@sample.org", deserializedResp[0].OriginalIdentifier.Identifier)
	assert.Equal(t, 1, len(deserializedResp[0].EntityRepresentations))
	assert.Equal(t, "bobid", deserializedResp[0].EntityRepresentations[0]["id"])
	assert.Equal(t, "alice@sample.org", deserializedResp[1].OriginalIdentifier.Identifier)
	assert.Equal(t, 1, len(deserializedResp[1].EntityRepresentations))
	assert.Equal(t, "aliceid", deserializedResp[1].EntityRepresentations[0]["id"])
}

func Test_ByGroupEmail(t *testing.T) {
	zapLog, _ := zap.NewDevelopment()

	server := test_server(t, map[string]string{
		"email=group1%40sample.org": "[]",
	}, map[string]string{
		"search=group1%40sample.org": `[{"id":"group1-uuid"}]`,
	}, map[string]string{
		"group1-uuid": group_resp,
	}, map[string]string{
		"group1-uuid": group_submember_resp,
	})
	defer server.Close()

	testReq := httptest.NewRequest(http.MethodPost, "http://test",
		strings.NewReader(`{"entity_identifiers": [{"type": "email","identifier": "group1@sample.org"}]}`))
	handler := GetEntityResolutionHandler(test_keycloakConfig(server), zapLog.Sugar())
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, testReq)

	resp := w.Result()
	defer resp.Body.Close()
	assert.Equal(t, http.StatusOK, resp.StatusCode)

	var deserializedResp []EntityResolution
	body, err := io.ReadAll(resp.Body)
	assert.Nil(t, err)
	err = json.Unmarshal(body, &deserializedResp)
	assert.Nil(t, err)
	assert.Equal(t, 1, len(deserializedResp))
	assert.Equal(t, "group1@sample.org", deserializedResp[0].OriginalIdentifier.Identifier)
	assert.Equal(t, 2, len(deserializedResp[0].EntityRepresentations))
	assert.Equal(t, "bobid", deserializedResp[0].EntityRepresentations[0]["id"])
	assert.Equal(t, "aliceid", deserializedResp[0].EntityRepresentations[1]["id"])
}

func Test_ByUsername(t *testing.T) {
	zapLog, _ := zap.NewDevelopment()

	server := test_server(t, map[string]string{
		"username=bob.smith": by_username_bob_resp,
	}, nil, nil, nil)
	defer server.Close()

	testReq := httptest.NewRequest(http.MethodPost, "http://test", strings.NewReader(`{"entity_identifiers": [{"type": "username","identifier": "bob.smith"}]}`))
	handler := GetEntityResolutionHandler(test_keycloakConfig(server), zapLog.Sugar())
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, testReq)

	resp := w.Result()
	defer resp.Body.Close()
	assert.Equal(t, http.StatusOK, resp.StatusCode)

	var deserializedResp []EntityResolution
	body, err := io.ReadAll(resp.Body)
	assert.Nil(t, err)
	err = json.Unmarshal(body, &deserializedResp)
	assert.Nil(t, err)
	assert.Equal(t, 1, len(deserializedResp))
	assert.Equal(t, "bob.smith", deserializedResp[0].OriginalIdentifier.Identifier)
	assert.Equal(t, 1, len(deserializedResp[0].EntityRepresentations))
	assert.Equal(t, "bobid", deserializedResp[0].EntityRepresentations[0]["id"])
}
