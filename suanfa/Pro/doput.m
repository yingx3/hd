function doput(Uw, Us)

global db n m basePath outputIndex

hw=Uw(:,:,1); uw=Uw(:,:,2)./Uw(:,:,1); vw=Uw(:,:,3)./Uw(:,:,1);
hs=Us(:,:,1); us=Us(:,:,2)./Us(:,:,1); vs=Us(:,:,3)./Us(:,:,1);
I=hw<=db; uw(I)=0; vw(I)=0; I=hs<=db; us(I)=0; vs(I)=0;

A=sqrt(us.^2+vs.^2); B=sqrt(uw.^2+vw.^2);

indexStr = int2str(outputIndex);

depthFileName = [basePath, 'depth1', filesep, indexStr, '.txt'];
fid=fopen(depthFileName,'wt');%写入文件路径
 for i=1:1:n
    for j=1:1:m
      if j==m
        fprintf(fid, '%3.4f \n',hs(i,j));
      else
        fprintf(fid, '%3.4f ',hs(i,j));
       end
    end
 end
fclose(fid);

depthFileName = [basePath, 'depth2', filesep, indexStr, '.txt'];
fid=fopen(depthFileName,'wt');%写入文件路径
 for i=1:1:n
    for j=1:1:m
       if j==m
         fprintf(fid, '%3.4f \n',hw(i,j));
       else
         fprintf(fid, '%3.4f ',hw(i,j));
       end
    end
 end
fclose(fid);

speedFileName = [basePath, 'speed1', filesep, indexStr, '.txt'];
fid=fopen(speedFileName,'wt');%写入文件路径
 for i=1:1:n
    for j=1:1:m
       if j==m
         fprintf(fid, '%3.4f \n',A(i,j));
       else
         fprintf(fid, '%3.4f ',A(i,j));
       end
    end
 end
fclose(fid);

speedFileName = [basePath, 'speed2', filesep, indexStr, '.txt'];
fid=fopen(speedFileName,'wt');%写入文件路径
 for i=1:1:n
    for j=1:1:m
       if j==m
         fprintf(fid, '%3.4f \n',B(i,j));
       else
         fprintf(fid, '%3.4f ',B(i,j));
       end
    end
 end
fclose(fid);

outputIndex = outputIndex + 1;