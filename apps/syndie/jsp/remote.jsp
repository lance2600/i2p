<%@page contentType="text/html" import="net.i2p.syndie.web.*" %>
<jsp:useBean scope="session" class="net.i2p.syndie.web.RemoteArchiveBean" id="remote" />
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" />
<jsp:useBean scope="session" class="net.i2p.syndie.data.ArchiveIndex" id="archive" />
<html>
<head>
<title>SyndieMedia</title>
<link href="style.jsp" rel="stylesheet" type="text/css" />
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr><td colspan="5" valign="top" align="left"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr><form action="remote.jsp" method="POST"><td valign="top" align="left" colspan="3">
<%
if (!user.getAuthenticated() || !user.getAllowAccessRemote()) { 
%>Sorry, you are not allowed to access remote archives from here.  Perhaps you should install Syndie yourself?<%
} else {
 %>Import from: 
<select name="schema">
 <option value="web">Web</option>
 <option value="eep">I2P</option>
 <option value="tor">TOR</option>
 <option value="freenet">Freenet</option>
 <option value="mnet">MNet</option>
 <option value="feedspace">Feedspace</option>
 <option value="usenet">Usenet</option>
</select>
<input name="location" size="60" /> <input type="submit" name="action" value="Continue..." />
<%
  String action = request.getParameter("action");
  if ("Continue...".equals(action)) {
    remote.fetchIndex(user, request.getParameter("schema"), request.getParameter("location"));
  } else if ("Fetch metadata".equals(action)) {
    remote.fetchMetadata(user, request.getParameterMap());
  } else if ("Fetch selected entries".equals(action)) {
    remote.fetchSelectedEntries(user, request.getParameterMap());
  } else if ("Fetch all new entries".equals(action)) {
    remote.fetchAllEntries(user, request.getParameterMap());
  }
  String msgs = remote.getStatus();
  if ( (msgs != null) && (msgs.length() > 0) ) { %><pre><%=msgs%>
<a href="remote.jsp">Refresh</a></pre><br /><% }
  if (remote.getFetchIndexInProgress()) { %><b>Please wait while the index is being fetched 
from <%=remote.getRemoteLocation()%></b>. <%
  } else if (remote.getRemoteIndex() != null) {
    // remote index is NOT null!
   %><b><%=remote.getRemoteLocation()%></b>:<br />
<%remote.renderDeltaForm(user, archive, out);%>
<textarea style="font-size:8pt" rows="5" cols="120"><%=remote.getRemoteIndex()%></textarea><%
  }
}
%>
</td></form></tr>
</table>
</body>